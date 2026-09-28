package cn.photolib.survey;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.form.FormFileService;
import cn.photolib.permission.PermissionCode;
import cn.photolib.recruitment.RecruitmentFormSchemaValidator;
import cn.photolib.recruitment.RecruitmentTimeConfig;
import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.survey.mapper.SurveyResponseMapper;
import cn.photolib.survey.model.SurveyEntity;
import cn.photolib.survey.model.SurveyResponseEntity;
import cn.photolib.survey.model.SurveyStatus;
import cn.photolib.uploadlimit.UploadLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问卷的两头：发放对象填写（{@code SURVEY_ACCESS}），以及查看、统计、导出结果
 * （{@code SURVEY_RESULT_VIEW}）。
 *
 * <p>能填写 = 在发放名单上 + 持有 SURVEY_ACCESS + 问卷进行中且没过截止时间。名单和权限两个
 * 条件都要：被移出名单的人、或者权限组后来被收回了问卷权限的人，都不能再交。
 *
 * <p>校区范围的结果查看人只看得到授权校区成员的答卷——列表、详情、统计和导出同一套口径。
 */
@Service
@RequiredArgsConstructor
public class SurveyResponseService {
    /** 同步导出整表进内存，行数上限和招募导出一致。 */
    static final int EXPORT_ROW_LIMIT = 10_000;

    private final SurveyService surveys;
    private final SurveyResponseMapper mapper;
    private final RecruitmentFormSchemaValidator schemaValidator;
    private final FormFileService formFiles;
    private final JdbcClient jdbc;
    private final UploadLimitService uploadLimits;

    // ------------------------------------------------------------------
    // 填写人
    // ------------------------------------------------------------------

    /** 发给我的问卷（草稿永远不出现），以及我交没交。 */
    public List<AssignedSurvey> assigned(AuthenticatedUser user) {
        requireAccess(user);
        Map<Long, LocalDateTime> submitted = new LinkedHashMap<>();
        jdbc.sql("SELECT survey_id, submitted_at FROM survey_response WHERE user_id=:userId")
                .param("userId", user.id())
                .query((row, index) -> Map.entry(row.getLong(1), row.getObject(2, LocalDateTime.class))).list()
                .forEach(entry -> submitted.put(entry.getKey(), entry.getValue()));
        return surveys.assignedTo(user.id()).stream()
                .map(survey -> new AssignedSurvey(survey.getId(), survey.getTitle(), survey.getDescription(),
                        survey.getEndsAt(), survey.getStatus(), surveys.isOpen(survey), survey.getPublishedAt(),
                        survey.getCreatorDisplayName(), submitted.get(survey.getId())))
                .toList();
    }

    /** 填写页：问卷内容；如果已经交过，连同自己的答卷一起给回去。 */
    public FillView fillView(long surveyId, AuthenticatedUser user) {
        SurveyEntity survey = requireRespondable(surveyId, user);
        SurveyResponseEntity mine = mapper.findByUser(surveyId, user.id());
        MyResponse response = null;
        if (mine != null) {
            RecruitmentFormSchema frozen = schemaValidator.readSchema(mine.getFormSchemaJson());
            Map<String, Object> answers = schemaValidator.readAnswers(mine.getAnswersJson());
            response = new MyResponse(mine.getId(), mine.getSubmittedAt(), frozen, answers,
                    formFiles.views(owner(surveyId, user.id()), FormFileService.fileIds(frozen, answers)));
        }
        return new FillView(survey.getId(), survey.getTitle(), survey.getDescription(), survey.getIntroMarkdown(),
                surveys.schema(survey), survey.getEndsAt(), survey.getStatus(), surveys.isOpen(survey),
                SurveyService.UploadLimits.current(uploadLimits), response);
    }

    @Transactional
    public FormFileService.UploadTicket createFileTicket(long surveyId, FormFileService.TicketRequest request,
                                                         AuthenticatedUser user) {
        SurveyEntity survey = requireOpenForSubmission(surveyId, user);
        return formFiles.createTicket(owner(surveyId, user.id()), surveys.schema(survey), request,
                survey.getEndsAt());
    }

    @Transactional
    public Receipt submit(long surveyId, Map<String, Object> answers, AuthenticatedUser user) {
        // 锁住问卷行：和「结束问卷」「改截止时间」排成一个先后，结束之后不会再有答卷落进来。
        surveys.requireForUpdate(surveyId);
        SurveyEntity survey = requireOpenForSubmission(surveyId, user);
        RecruitmentFormSchema schema = surveys.schema(survey);
        Map<String, Object> checked = formFiles.attachAnswers(owner(surveyId, user.id()), schema,
                schemaValidator.validateAnswers(schema, answers));
        LocalDateTime now = surveys.now();
        SurveyResponseEntity response = new SurveyResponseEntity();
        response.setSurveyId(surveyId);
        response.setUserId(user.id());
        response.setAnswersJson(schemaValidator.answersJson(checked));
        response.setFormSchemaJson(schemaValidator.schemaJson(schema));
        response.setSubmittedAt(now);
        response.setCreatedAt(now);
        try {
            mapper.insert(response);
        } catch (DuplicateKeyException exception) {
            throw alreadySubmitted();
        }
        return new Receipt(response.getId(), now);
    }

    // ------------------------------------------------------------------
    // 结果查看人
    // ------------------------------------------------------------------

    public PageResponse<ResponseSummary> list(long surveyId, int page, int pageSize, String keyword,
                                              AuthenticatedUser user) {
        requireResults(user);
        SurveyEntity survey = requireVisibleSurvey(surveyId);
        Collection<Long> scope = user.scopedCampusIds();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        String cleanKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        long total = mapper.count(survey.getId(), cleanKeyword, scope);
        List<ResponseSummary> items = mapper.findPage(survey.getId(), cleanKeyword, scope, safePageSize,
                        (long) (safePage - 1) * safePageSize).stream()
                .map(response -> new ResponseSummary(response.getId(), response.getUserId(),
                        response.getDisplayName(), response.getUsername(), response.getPermissionGroupName(),
                        response.getSubmittedAt()))
                .toList();
        return new PageResponse<>(items, safePage, safePageSize, total,
                total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
    }

    /** 发放名单和各人的提交情况，用来催交。 */
    public List<TargetStatus> targets(long surveyId, AuthenticatedUser user) {
        if (user == null || !user.hasAnyPermission(PermissionCode.SURVEY_CREATE, PermissionCode.SURVEY_RESULT_VIEW)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看问卷发放名单");
        }
        SurveyEntity survey = surveys.requireSurvey(surveyId);
        if (survey.getStatus() == SurveyStatus.DRAFT && !user.hasPermission(PermissionCode.SURVEY_CREATE)) {
            throw SurveyService.notFound();
        }
        return targetStatuses(surveyId, user.scopedCampusIds());
    }

    public ResponseDetail get(long responseId, AuthenticatedUser user) {
        requireResults(user);
        SurveyResponseEntity response = mapper.findDetail(responseId);
        if (response == null || !inScope(response.getUserId(), user.scopedCampusIds())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "答卷不存在");
        }
        SurveyEntity survey = requireVisibleSurvey(response.getSurveyId());
        RecruitmentFormSchema schema = schemaValidator.readSchema(response.getFormSchemaJson());
        Map<String, Object> answers = schemaValidator.readAnswers(response.getAnswersJson());
        return new ResponseDetail(response.getId(), survey.getId(), survey.getTitle(), response.getUserId(),
                response.getDisplayName(), response.getUsername(), response.getPermissionGroupName(),
                response.getSubmittedAt(), schema, answers,
                formFiles.views(owner(survey.getId(), response.getUserId()), FormFileService.fileIds(schema, answers)));
    }

    /** 选择题统计：按问卷当前的题目，数每个选项被选了几次。 */
    public Summary summary(long surveyId, AuthenticatedUser user) {
        requireResults(user);
        SurveyEntity survey = requireVisibleSurvey(surveyId);
        List<SurveyResponseEntity> responses = allResponses(survey.getId(), user.scopedCampusIds());
        return summarize(surveys.schema(survey), responses);
    }

    public Export export(long surveyId, AuthenticatedUser user) {
        requireResults(user);
        SurveyEntity survey = requireVisibleSurvey(surveyId);
        Collection<Long> scope = user.scopedCampusIds();
        List<SurveyResponseEntity> responses = allResponses(survey.getId(), scope);
        RecruitmentFormSchema schema = surveys.schema(survey);
        List<SurveyResponseExport.Entry> entries = responses.stream()
                .map(response -> new SurveyResponseExport.Entry(response.getDisplayName(), response.getUsername(),
                        response.getPermissionGroupName(), response.getSubmittedAt(),
                        schemaValidator.readSchema(response.getFormSchemaJson()),
                        schemaValidator.readAnswers(response.getAnswersJson())))
                .toList();
        List<SurveyResponseExport.Pending> pending = targetStatuses(survey.getId(), scope).stream()
                .filter(target -> target.submittedAt() == null)
                .map(target -> new SurveyResponseExport.Pending(target.displayName(), target.username(),
                        target.permissionGroupName(), String.join("、", target.campusNames())))
                .toList();
        byte[] content = SurveyResponseExport.workbook(schema, entries, pending, summarize(schema, responses));
        return new Export(SurveyResponseExport.fileName(survey.getTitle(),
                LocalDate.now(RecruitmentTimeConfig.ZONE)), content);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<SurveyResponseEntity> allResponses(long surveyId, Collection<Long> scope) {
        long total = mapper.count(surveyId, null, scope);
        if (total > EXPORT_ROW_LIMIT) {
            throw SurveyService.validation("答卷超过 " + EXPORT_ROW_LIMIT + " 份，暂不支持一次导出或统计");
        }
        List<SurveyResponseEntity> responses = new ArrayList<>(mapper.findPage(surveyId, null, scope,
                EXPORT_ROW_LIMIT, 0));
        // 导出按提交先后排，读起来是一张签到表；列表页才是最新的在前。
        responses.sort((left, right) -> left.getSubmittedAt().compareTo(right.getSubmittedAt()));
        return responses;
    }

    static Summary summarize(RecruitmentFormSchema schema, List<SurveyResponseEntity> responses,
                             RecruitmentFormSchemaValidator validator) {
        List<Map<String, Object>> answers = responses.stream()
                .map(response -> validator.readAnswers(response.getAnswersJson())).toList();
        List<FieldSummary> fields = new ArrayList<>();
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            if (field.type() != RecruitmentFieldType.SINGLE_CHOICE
                    && field.type() != RecruitmentFieldType.MULTIPLE_CHOICE) continue;
            Map<String, Long> counts = new LinkedHashMap<>();
            field.options().forEach(option -> counts.put(option, 0L));
            long answered = 0;
            for (Map<String, Object> answer : answers) {
                Object value = answer.get(field.id());
                List<?> selected = value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
                if (!selected.isEmpty()) answered++;
                for (Object option : selected) {
                    String key = String.valueOf(option);
                    // 答卷冻结的是提交那一刻的选项；问卷发布后题目不能改，这里只是防御旧数据。
                    if (counts.containsKey(key)) counts.merge(key, 1L, Long::sum);
                }
            }
            fields.add(new FieldSummary(field.id(), field.label(), field.type(), answered,
                    counts.entrySet().stream().map(entry -> new OptionCount(entry.getKey(), entry.getValue()))
                            .toList()));
        }
        return new Summary(responses.size(), fields);
    }

    private Summary summarize(RecruitmentFormSchema schema, List<SurveyResponseEntity> responses) {
        return summarize(schema, responses, schemaValidator);
    }

    private List<TargetStatus> targetStatuses(long surveyId, Collection<Long> scope) {
        Map<Long, Set<Long>> campuses = surveys.campusesByUser();
        Map<Long, String> campusNames = surveys.campusNames();
        return jdbc.sql("""
                        SELECT u.id, u.display_name, u.username, pg.name AS group_name,
                               r.id AS response_id, r.submitted_at
                        FROM survey_target t
                        JOIN app_user u ON u.id=t.user_id
                        LEFT JOIN permission_group pg ON pg.id=COALESCE(u.permission_group_id,
                            (SELECT legacy_pg.id FROM permission_group legacy_pg WHERE legacy_pg.code=u.role))
                        LEFT JOIN survey_response r ON r.survey_id=t.survey_id AND r.user_id=t.user_id
                        WHERE t.survey_id=:surveyId
                        ORDER BY u.display_name, u.id
                        """).param("surveyId", surveyId)
                .query((row, index) -> {
                    long userId = row.getLong("id");
                    long responseId = row.getLong("response_id");
                    Long response = row.wasNull() ? null : responseId;
                    List<Long> userCampuses = List.copyOf(campuses.getOrDefault(userId, Set.of()));
                    return new TargetStatus(userId, row.getString("display_name"), row.getString("username"),
                            row.getString("group_name"), userCampuses,
                            userCampuses.stream().map(campusNames::get).filter(name -> name != null).toList(),
                            response, row.getObject("submitted_at", LocalDateTime.class));
                })
                .list().stream()
                .filter(target -> scope.isEmpty() || target.campusIds().stream().anyMatch(scope::contains))
                .toList();
    }

    private boolean inScope(Long userId, Collection<Long> scope) {
        if (scope.isEmpty()) return true;
        return surveys.campusesByUser().getOrDefault(userId, Set.of()).stream().anyMatch(scope::contains);
    }

    private SurveyEntity requireRespondable(long surveyId, AuthenticatedUser user) {
        requireAccess(user);
        SurveyEntity survey = surveys.requireSurvey(surveyId);
        // 草稿、以及没发给自己的问卷，一律当作不存在：不透露它的存在本身。
        if (survey.getStatus() == SurveyStatus.DRAFT || !surveys.isTarget(surveyId, user.id())) {
            throw SurveyService.notFound();
        }
        return survey;
    }

    private SurveyEntity requireOpenForSubmission(long surveyId, AuthenticatedUser user) {
        SurveyEntity survey = requireRespondable(surveyId, user);
        if (!surveys.isOpen(survey)) throw SurveyService.conflict("问卷已经结束，不能再提交");
        if (mapper.findByUser(surveyId, user.id()) != null) throw alreadySubmitted();
        return survey;
    }

    private SurveyEntity requireVisibleSurvey(long surveyId) {
        SurveyEntity survey = surveys.requireSurvey(surveyId);
        if (survey.getStatus() == SurveyStatus.DRAFT) throw SurveyService.notFound();
        return survey;
    }

    private static FormFileService.Owner owner(long surveyId, long userId) {
        return FormFileService.Owner.surveyRespondent(surveyId, userId);
    }

    private static void requireAccess(AuthenticatedUser user) {
        SurveyService.requirePermission(user, PermissionCode.SURVEY_ACCESS);
    }

    private static void requireResults(AuthenticatedUser user) {
        if (user == null || !user.hasPermission(PermissionCode.SURVEY_RESULT_VIEW)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看问卷结果");
        }
    }

    private static BusinessException alreadySubmitted() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "你已经提交过这份问卷了，每人只能交一次");
    }

    public record AssignedSurvey(Long id, String title, String description, LocalDateTime endsAt,
                                 SurveyStatus status, boolean open, LocalDateTime publishedAt,
                                 String creatorDisplayName, LocalDateTime submittedAt) {
    }

    public record MyResponse(Long id, LocalDateTime submittedAt, RecruitmentFormSchema formSchema,
                             Map<String, Object> answers, List<FormFileService.FileView> files) {
    }

    public record FillView(Long id, String title, String description, String introMarkdown,
                           RecruitmentFormSchema formSchema, LocalDateTime endsAt, SurveyStatus status,
                           boolean open, SurveyService.UploadLimits uploadLimits, MyResponse myResponse) {
    }

    public record Receipt(Long responseId, LocalDateTime submittedAt) {
    }

    public record ResponseSummary(Long id, Long userId, String displayName, String username,
                                  String permissionGroupName, LocalDateTime submittedAt) {
    }

    public record TargetStatus(Long userId, String displayName, String username, String permissionGroupName,
                               List<Long> campusIds, List<String> campusNames, Long responseId,
                               LocalDateTime submittedAt) {
    }

    public record ResponseDetail(Long id, Long surveyId, String surveyTitle, Long userId, String displayName,
                                 String username, String permissionGroupName, LocalDateTime submittedAt,
                                 RecruitmentFormSchema formSchema, Map<String, Object> answers,
                                 List<FormFileService.FileView> files) {
    }

    public record OptionCount(String option, long count) {
    }

    public record FieldSummary(String fieldId, String label, RecruitmentFieldType type, long answeredCount,
                               List<OptionCount> options) {
    }

    public record Summary(long responseCount, List<FieldSummary> fields) {
    }

    public record Export(String fileName, byte[] content) {
    }
}

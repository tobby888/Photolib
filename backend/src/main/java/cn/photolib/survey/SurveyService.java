package cn.photolib.survey;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.form.FormFileService;
import cn.photolib.notification.NotificationService;
import cn.photolib.permission.PermissionCode;
import cn.photolib.recruitment.RecruitmentFormSchemaValidator;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.survey.mapper.SurveyMapper;
import cn.photolib.survey.model.SurveyEntity;
import cn.photolib.survey.model.SurveyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 问卷本身：新建、编辑、发布、结束，以及发放对象。
 *
 * <p>三个权限各管一段，后端是唯一的授权边界：
 * <ul>
 *   <li>{@code SURVEY_CREATE} 管问卷和发放对象；只能从「持有 SURVEY_ACCESS 的启用账号」里挑人，
 *       校区范围的发起人只能挑和自己有共同授权校区的人；</li>
 *   <li>{@code SURVEY_ACCESS} 管填写，见 {@link SurveyResponseService}；</li>
 *   <li>{@code SURVEY_RESULT_VIEW} 管看结果和导出，同样在 {@link SurveyResponseService}。</li>
 * </ul>
 *
 * <p>和招募一样，发布之后题目冻结（已经有人在填了）；标题、描述、简介、截止时间和发放对象
 * 仍然可以改——后来补发的人会收到站内通知。
 */
@Service
@RequiredArgsConstructor
public class SurveyService {
    static final String EVENT_PUBLISHED = "SURVEY_PUBLISHED";
    static final int MAX_TARGETS = 5_000;
    private static final int MAX_INTRO = 20_000;

    /**
     * 「问卷对象」的定义：启用、未删除，且权限组带 {@code SURVEY_ACCESS}。
     * 候选名单、发放校验和填写校验都从这一份里取，避免几份 SQL 慢慢走样。
     */
    static final String RESPONDENTS_FROM = """
            FROM app_user u
            JOIN permission_group pg
              ON pg.id = COALESCE(u.permission_group_id,
                  (SELECT legacy_pg.id FROM permission_group legacy_pg
                   WHERE legacy_pg.code = u.role))
            JOIN permission_group_permission p
              ON p.group_id = pg.id AND p.permission_code = 'SURVEY_ACCESS'
            WHERE u.enabled = TRUE AND u.deleted = FALSE AND pg.deleted = FALSE
            """;

    private final SurveyMapper mapper;
    private final RecruitmentFormSchemaValidator schemaValidator;
    private final NotificationService notifications;
    private final JdbcClient jdbc;
    private final Clock recruitmentClock;

    // ------------------------------------------------------------------
    // 发起人
    // ------------------------------------------------------------------

    @Transactional
    public SurveyView create(SurveyCommand command, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.SURVEY_CREATE);
        Validated input = validate(command);
        Set<Long> targets = requireEligibleTargets(input.targetUserIds(), user, Set.of());
        SurveyEntity survey = new SurveyEntity();
        apply(survey, input);
        survey.setStatus(SurveyStatus.DRAFT);
        survey.setCreatedBy(user.id());
        mapper.insert(survey);
        replaceTargets(survey.getId(), targets);
        return loadView(survey.getId(), user, true);
    }

    @Transactional
    public SurveyView update(long id, SurveyCommand command, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.SURVEY_CREATE);
        SurveyEntity survey = requireForUpdate(id);
        if (survey.getStatus() == SurveyStatus.CLOSED) throw conflict("已结束的问卷不能再编辑");
        Validated input = validate(command);
        if (survey.getStatus() == SurveyStatus.PUBLISHED
                && !schemaValidator.schemaJson(schemaValidator.readSchema(survey.getFormSchemaJson()))
                .equals(input.formSchemaJson())) {
            throw conflict("问卷发布后题目不能再改，只能改标题、描述、简介、截止时间和发放对象");
        }
        Set<Long> previous = targetIds(id);
        // 以前就在名单上的人即使已经超出当前编辑人的校区范围，也原样保留，
        // 否则校区负责人改一个标题就会被别人加的对象卡住。
        Set<Long> targets = requireEligibleTargets(input.targetUserIds(), user, previous);
        if (survey.getStatus() == SurveyStatus.PUBLISHED && targets.isEmpty()) {
            throw validation("已发布的问卷至少要发给一个人");
        }
        apply(survey, input);
        survey.setVersion(requireVersion(version));
        updateChecked(survey);
        replaceTargets(id, targets);
        if (survey.getStatus() == SurveyStatus.PUBLISHED) {
            Set<Long> added = new LinkedHashSet<>(targets);
            added.removeAll(previous);
            notifyTargets(survey, added);
        }
        return loadView(id, user, true);
    }

    @Transactional
    public SurveyView publish(long id, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.SURVEY_CREATE);
        SurveyEntity survey = requireForUpdate(id);
        if (survey.getStatus() != SurveyStatus.DRAFT) throw conflict("只有草稿问卷可以发布");
        LocalDateTime now = now();
        if (survey.getEndsAt() != null && !survey.getEndsAt().isAfter(now)) {
            throw conflict("截止时间已经过了，改一下截止时间再发布");
        }
        if (schemaValidator.readSchema(survey.getFormSchemaJson()).fields().isEmpty()) {
            throw conflict("问卷还没有题目");
        }
        Set<Long> targets = targetIds(id);
        if (targets.isEmpty()) throw conflict("还没有选发给谁");
        survey.setStatus(SurveyStatus.PUBLISHED);
        survey.setPublishedBy(user.id());
        survey.setPublishedAt(now);
        survey.setVersion(requireVersion(version));
        updateChecked(survey);
        notifyTargets(survey, targets);
        return loadView(id, user, true);
    }

    @Transactional
    public SurveyView close(long id, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.SURVEY_CREATE);
        SurveyEntity survey = requireForUpdate(id);
        if (survey.getStatus() != SurveyStatus.PUBLISHED) throw conflict("只有进行中的问卷可以结束");
        survey.setStatus(SurveyStatus.CLOSED);
        survey.setClosedBy(user.id());
        survey.setClosedAt(now());
        survey.setVersion(requireVersion(version));
        updateChecked(survey);
        return loadView(id, user, true);
    }

    /** 只有草稿能删：发布过的问卷已经有人看到甚至填了，删掉就对不上账了。 */
    @Transactional
    public void delete(long id, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.SURVEY_CREATE);
        SurveyEntity survey = requireForUpdate(id);
        if (survey.getStatus() != SurveyStatus.DRAFT) throw conflict("只有草稿问卷可以删除，发布过的问卷请结束它");
        if (survey.getVersion() == null || survey.getVersion() != requireVersion(version)) {
            throw conflict("问卷已被其他操作修改，请刷新后重试");
        }
        jdbc.sql("DELETE FROM survey_target WHERE survey_id=:id").param("id", id).update();
        mapper.deleteById(id);
    }

    /** 管理列表：发起人看得到草稿，只能看结果的人只看发布过的问卷。 */
    public PageResponse<SurveyView> list(int page, int pageSize, String keyword, SurveyStatus status,
                                         AuthenticatedUser user) {
        requireManageView(user);
        boolean includeDrafts = user.hasPermission(PermissionCode.SURVEY_CREATE);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        String cleanKeyword = cleanOptional(keyword, 200, "关键词");
        long total = mapper.countPage(includeDrafts, status, cleanKeyword);
        List<SurveyView> items = mapper.findPage(includeDrafts, status, cleanKeyword, safePageSize,
                        (long) (safePage - 1) * safePageSize).stream()
                .map(survey -> toView(survey, null))
                .toList();
        return new PageResponse<>(items, safePage, safePageSize, total,
                total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
    }

    public SurveyView get(long id, AuthenticatedUser user) {
        requireManageView(user);
        return loadView(id, user, user.hasPermission(PermissionCode.SURVEY_CREATE));
    }

    /**
     * 选择发放对象用的候选名单，以及筛选器要用的权限组和校区。只列持有 SURVEY_ACCESS 的
     * 启用账号：没有访问权限的人就算被选上也打不开问卷，干脆不让选。
     */
    public AudienceOptions audienceOptions(AuthenticatedUser user) {
        requirePermission(user, PermissionCode.SURVEY_CREATE);
        List<AudienceCandidate> candidates = candidates(user);
        Map<Long, String> groups = new LinkedHashMap<>();
        Set<Long> campusIds = new TreeSet<>();
        for (AudienceCandidate candidate : candidates) {
            groups.putIfAbsent(candidate.permissionGroupId(), candidate.permissionGroupName());
            campusIds.addAll(candidate.campusIds());
        }
        Map<Long, String> campusNames = campusNames();
        List<Option> campuses = campusIds.stream()
                .filter(campusNames::containsKey)
                .map(campusId -> new Option(campusId, campusNames.get(campusId)))
                .toList();
        List<Option> groupOptions = groups.entrySet().stream()
                .map(entry -> new Option(entry.getKey(), entry.getValue())).toList();
        return new AudienceOptions(candidates, groupOptions, campuses);
    }

    // ------------------------------------------------------------------
    // 给 SurveyResponseService 用的
    // ------------------------------------------------------------------

    SurveyEntity requireSurvey(long id) {
        SurveyEntity survey = mapper.selectById(id);
        if (survey == null) throw notFound();
        return survey;
    }

    SurveyEntity requireForUpdate(long id) {
        SurveyEntity survey = mapper.findByIdForUpdate(id);
        if (survey == null) throw notFound();
        return survey;
    }

    Set<Long> targetIds(long surveyId) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT user_id FROM survey_target WHERE survey_id=:surveyId ORDER BY user_id
                """).param("surveyId", surveyId).query(Long.class).list());
    }

    boolean isTarget(long surveyId, long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM survey_target WHERE survey_id=:surveyId AND user_id=:userId")
                .param("surveyId", surveyId).param("userId", userId).query(Long.class).single() > 0;
    }

    boolean isOpen(SurveyEntity survey) {
        return survey.getStatus() == SurveyStatus.PUBLISHED
                && (survey.getEndsAt() == null || survey.getEndsAt().isAfter(now()));
    }

    RecruitmentFormSchema schema(SurveyEntity survey) {
        return schemaValidator.readSchema(survey.getFormSchemaJson());
    }

    List<SurveyEntity> assignedTo(long userId) {
        return mapper.findAssignedTo(userId);
    }

    /** 每个用户的授权校区（user_campus_permission 加上旧的 app_user.campus_id）。 */
    Map<Long, Set<Long>> campusesByUser() {
        Map<Long, Set<Long>> result = new HashMap<>();
        jdbc.sql("SELECT user_id, campus_id FROM user_campus_permission")
                .query((row, index) -> Map.entry(row.getLong(1), row.getLong(2))).list()
                .forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>())
                        .add(entry.getValue()));
        jdbc.sql("SELECT id, campus_id FROM app_user WHERE campus_id IS NOT NULL AND deleted=FALSE")
                .query((row, index) -> Map.entry(row.getLong(1), row.getLong(2))).list()
                .forEach(entry -> result.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>())
                        .add(entry.getValue()));
        return result;
    }

    Map<Long, String> campusNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM campus WHERE deleted=FALSE ORDER BY id")
                .query((row, index) -> Map.entry(row.getLong(1), row.getString(2))).list()
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    LocalDateTime now() {
        return LocalDateTime.now(recruitmentClock);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<AudienceCandidate> candidates(AuthenticatedUser user) {
        Map<Long, Set<Long>> campuses = campusesByUser();
        Set<Long> scope = user.isCampusScoped() ? user.scopedCampusIds() : Set.of();
        return jdbc.sql("""
                        SELECT u.id, u.display_name, u.username, pg.id AS group_id, pg.name AS group_name
                        """ + RESPONDENTS_FROM + """
                        ORDER BY u.display_name, u.id
                        """)
                .query((row, index) -> new AudienceCandidate(row.getLong("id"), row.getString("display_name"),
                        row.getString("username"), row.getLong("group_id"), row.getString("group_name"),
                        List.copyOf(campuses.getOrDefault(row.getLong("id"), Set.of()))))
                .list().stream()
                .filter(candidate -> scope.isEmpty()
                        || candidate.campusIds().stream().anyMatch(scope::contains))
                .toList();
    }

    private Set<Long> requireEligibleTargets(List<Long> requested, AuthenticatedUser user, Set<Long> retained) {
        Set<Long> unique = new LinkedHashSet<>();
        if (requested != null) {
            for (Long id : requested) if (id != null) unique.add(id);
        }
        if (unique.size() > MAX_TARGETS) throw validation("一份问卷最多发给 " + MAX_TARGETS + " 人");
        if (unique.isEmpty()) return unique;
        Set<Long> eligible = new LinkedHashSet<>(retained);
        candidates(user).forEach(candidate -> eligible.add(candidate.id()));
        for (Long id : unique) {
            if (!eligible.contains(id)) {
                throw validation("发放对象里有人没有问卷访问权限，或不在你能选择的范围内，请刷新名单后重选");
            }
        }
        return unique;
    }

    private void replaceTargets(long surveyId, Set<Long> targets) {
        jdbc.sql("DELETE FROM survey_target WHERE survey_id=:surveyId").param("surveyId", surveyId).update();
        LocalDateTime now = now();
        for (Long userId : targets) {
            jdbc.sql("INSERT INTO survey_target(survey_id, user_id, created_at) VALUES (:surveyId, :userId, :now)")
                    .param("surveyId", surveyId).param("userId", userId).param("now", now).update();
        }
    }

    private void notifyTargets(SurveyEntity survey, Collection<Long> userIds) {
        if (userIds.isEmpty()) return;
        String deadline = survey.getEndsAt() == null ? "请抽空填写。"
                : "请在 " + survey.getEndsAt().toLocalDate() + " " + survey.getEndsAt().toLocalTime().withNano(0)
                  + " 前填写。";
        String html = NotificationService.paragraphs(survey.getDescription(), deadline);
        String actionUrl = "/surveys/" + survey.getId() + "/fill";
        for (Long userId : userIds) {
            notifications.notifyUser(userId, EVENT_PUBLISHED, "新问卷：" + survey.getTitle(), html, actionUrl);
        }
    }

    private SurveyView loadView(long id, AuthenticatedUser user, boolean includeTargets) {
        SurveyEntity survey = mapper.findViewById(id);
        if (survey == null) throw notFound();
        if (survey.getStatus() == SurveyStatus.DRAFT && !user.hasPermission(PermissionCode.SURVEY_CREATE)) {
            throw notFound();
        }
        return toView(survey, includeTargets ? List.copyOf(targetIds(id)) : null);
    }

    private SurveyView toView(SurveyEntity survey, List<Long> targetUserIds) {
        return new SurveyView(survey.getId(), survey.getTitle(), survey.getDescription(),
                survey.getIntroMarkdown(), schemaValidator.readSchema(survey.getFormSchemaJson()),
                survey.getEndsAt(), survey.getStatus(), isOpen(survey), survey.getCreatedBy(),
                survey.getCreatorDisplayName(), survey.getPublishedAt(), survey.getClosedAt(),
                survey.getTargetCount() == null ? 0 : survey.getTargetCount(),
                survey.getResponseCount() == null ? 0 : survey.getResponseCount(),
                targetUserIds, survey.getVersion(), survey.getCreatedAt(), survey.getUpdatedAt());
    }

    private Validated validate(SurveyCommand command) {
        if (command == null) throw validation("问卷内容不能为空");
        String title = cleanRequired(command.title(), 200, "问卷标题");
        String description = cleanOptional(command.description(), 1_000, "问卷描述");
        String intro = command.introMarkdown() == null || command.introMarkdown().isBlank()
                ? null : command.introMarkdown().strip();
        if (intro != null && intro.codePointCount(0, intro.length()) > MAX_INTRO) {
            throw validation("问卷简介最多 " + MAX_INTRO + " 个字符");
        }
        RecruitmentFormSchema schema = schemaValidator.validate(command.formSchema());
        if (schema.fields().isEmpty()) throw validation("问卷至少要有一道题");
        if (command.endsAt() != null && !command.endsAt().isAfter(now())) {
            throw validation("截止时间必须晚于现在");
        }
        return new Validated(title, description, intro, schemaValidator.schemaJson(schema), command.endsAt(),
                command.targetUserIds() == null ? List.of() : new ArrayList<>(command.targetUserIds()));
    }

    private static void apply(SurveyEntity survey, Validated input) {
        survey.setTitle(input.title());
        survey.setDescription(input.description());
        survey.setIntroMarkdown(input.introMarkdown());
        survey.setFormSchemaJson(input.formSchemaJson());
        survey.setEndsAt(input.endsAt());
    }

    private void updateChecked(SurveyEntity survey) {
        if (mapper.updateById(survey) != 1) throw conflict("问卷已被其他操作修改，请刷新后重试");
    }

    private static void requireManageView(AuthenticatedUser user) {
        if (user == null || !user.hasAnyPermission(PermissionCode.SURVEY_CREATE, PermissionCode.SURVEY_RESULT_VIEW)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理问卷");
        }
    }

    static void requirePermission(AuthenticatedUser user, PermissionCode permission) {
        if (user == null || !user.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该问卷操作");
        }
    }

    private static int requireVersion(int version) {
        if (version < 1) throw validation("版本号不合法");
        return version;
    }

    private static String cleanRequired(String value, int max, String field) {
        String clean = cleanOptional(value, max, field);
        if (clean == null) throw validation(field + "不能为空");
        return clean;
    }

    private static String cleanOptional(String value, int max, String field) {
        if (value == null) return null;
        String clean = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
        if (clean.codePointCount(0, clean.length()) > max) throw validation(field + "最多 " + max + " 个字符");
        return clean.isEmpty() ? null : clean;
    }

    static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "问卷不存在");
    }

    static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, message);
    }

    public record SurveyCommand(String title, String description, String introMarkdown,
                                RecruitmentFormSchema formSchema, LocalDateTime endsAt,
                                List<Long> targetUserIds) {
    }

    public record SurveyView(Long id, String title, String description, String introMarkdown,
                             RecruitmentFormSchema formSchema, LocalDateTime endsAt, SurveyStatus status,
                             boolean open, Long createdBy, String creatorDisplayName,
                             LocalDateTime publishedAt, LocalDateTime closedAt, long targetCount,
                             long responseCount, List<Long> targetUserIds, Integer version,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record AudienceCandidate(Long id, String displayName, String username, Long permissionGroupId,
                                    String permissionGroupName, List<Long> campusIds) {
    }

    public record Option(Long id, String name) {
    }

    public record AudienceOptions(List<AudienceCandidate> candidates, List<Option> permissionGroups,
                                  List<Option> campuses) {
    }

    /** {@link FormFileService} 限制单个文件大小；这里只是把前端需要知道的数一并给出。 */
    public record UploadLimits(long maxFileBytes, int maxFilesPerField) {
        static final UploadLimits DEFAULT = new UploadLimits(FormFileService.MAX_FILE_BYTES,
                RecruitmentFormSchemaValidator.MAX_FILES_PER_FIELD);
    }

    private record Validated(String title, String description, String introMarkdown, String formSchemaJson,
                             LocalDateTime endsAt, List<Long> targetUserIds) {
    }
}

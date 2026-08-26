package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.permission.PermissionCode;
import cn.photolib.recruitment.mapper.RecruitmentDraftMapper;
import cn.photolib.recruitment.mapper.RecruitmentTaskMapper;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.recruitment.model.RecruitmentTaskEntity;
import cn.photolib.recruitment.model.RecruitmentTaskStatus;
import cn.photolib.recruitment.upload.RecruitmentUploadProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecruitmentTaskService {
    private final RecruitmentTaskMapper mapper;
    private final RecruitmentDraftMapper draftMapper;
    private final RecruitmentFormSchemaValidator schemaValidator;
    private final RecruitmentUploadProperties uploadProperties;
    private final Clock recruitmentClock;

    @Transactional
    public TaskView create(TaskCommand command, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.RECRUITMENT_PUBLISH);
        ValidatedCommand input = validate(command);
        RecruitmentTaskEntity task = new RecruitmentTaskEntity();
        task.setPublicId(PublicId.next());
        apply(task, input);
        task.setStatus(RecruitmentTaskStatus.DRAFT);
        task.setCreatedBy(user.id());
        mapper.insert(task);
        return loadView(task.getId());
    }

    @Transactional
    public TaskView update(long id, TaskCommand command, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.RECRUITMENT_PUBLISH);
        RecruitmentTaskEntity task = requireTaskForUpdate(id);
        if (task.getStatus() == RecruitmentTaskStatus.CLOSED) {
            throw conflict("已关闭的招募任务不能编辑");
        }
        ValidatedCommand input = validate(command);
        if (task.getStatus() == RecruitmentTaskStatus.PUBLISHED
                && formConfigurationChanged(task, input)) {
            throw conflict("任务发布后表单结构、学号项和附件项不能修改");
        }
        LocalDateTime previousEndsAt = task.getEndsAt();
        apply(task, input);
        task.setVersion(requireVersion(version));
        updateChecked(task);
        if (task.getStatus() == RecruitmentTaskStatus.PUBLISHED
                && !previousEndsAt.equals(input.endsAt())) {
            draftMapper.synchronizeOpenDraftExpiry(task.getId(), input.endsAt(), now());
        }
        return loadView(id);
    }

    @Transactional
    public TaskView publish(long id, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.RECRUITMENT_PUBLISH);
        RecruitmentTaskEntity task = requireTaskForUpdate(id);
        if (task.getStatus() != RecruitmentTaskStatus.DRAFT) {
            throw conflict("只有草稿招募任务可以发布");
        }
        LocalDateTime now = now();
        if (!task.getEndsAt().isAfter(now)) {
            throw conflict("招募结束时间已过，不能发布");
        }
        // Decode and revalidate before making a schema externally visible. This also
        // protects old rows if a future migration introduces malformed JSON.
        schemaValidator.readSchema(task.getFormSchemaJson());
        task.setStatus(RecruitmentTaskStatus.PUBLISHED);
        task.setPublishedBy(user.id());
        task.setPublishedAt(now);
        task.setVersion(requireVersion(version));
        updateChecked(task);
        return loadView(id);
    }

    @Transactional
    public TaskView close(long id, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.RECRUITMENT_PUBLISH);
        RecruitmentTaskEntity task = requireTaskForUpdate(id);
        if (task.getStatus() != RecruitmentTaskStatus.PUBLISHED) {
            throw conflict("只有已发布的招募任务可以关闭");
        }
        task.setStatus(RecruitmentTaskStatus.CLOSED);
        task.setClosedBy(user.id());
        LocalDateTime closedAt = now();
        task.setClosedAt(closedAt);
        task.setVersion(requireVersion(version));
        updateChecked(task);
        draftMapper.expireOpenDrafts(task.getId(), closedAt);
        return loadView(id);
    }

    public PageResponse<TaskView> list(int page, int pageSize, String keyword,
                                       RecruitmentTaskStatus status, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.RECRUITMENT_VIEW);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        String cleanKeyword = cleanOptional(keyword, 200, "关键词");
        long total = mapper.countPage(status, cleanKeyword);
        List<TaskView> items = mapper.findPage(status, cleanKeyword, safePageSize,
                        (long) (safePage - 1) * safePageSize).stream()
                .map(this::toView)
                .toList();
        return new PageResponse<>(items, safePage, safePageSize, total,
                total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
    }

    public TaskView get(long id, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.RECRUITMENT_VIEW);
        return loadView(id);
    }

    private TaskView loadView(long id) {
        RecruitmentTaskEntity task = mapper.findViewById(id);
        if (task == null) throw notFound();
        return toView(task);
    }

    public List<PublicTaskView> active() {
        return mapper.findActive(now()).stream().map(this::toPublicView).toList();
    }

    RecruitmentTaskEntity requireTask(long id) {
        RecruitmentTaskEntity task = mapper.selectById(id);
        if (task == null) throw notFound();
        return task;
    }

    private RecruitmentTaskEntity requireTaskForUpdate(long id) {
        RecruitmentTaskEntity task = mapper.findByIdForUpdate(id);
        if (task == null) throw notFound();
        return task;
    }

    RecruitmentTaskEntity requireByPublicId(String publicId) {
        if (publicId == null || !publicId.matches("[0-9A-HJKMNP-TV-Z]{26}")) {
            throw notFound();
        }
        RecruitmentTaskEntity task = mapper.findByPublicId(publicId);
        if (task == null) throw notFound();
        return task;
    }

    /** Validates an externally supplied id/window before allocating anonymous limiter state. */
    public String requireActivePublicId(String publicId) {
        return requireActive(publicId).getPublicId();
    }

    RecruitmentTaskEntity requireByPublicIdForUpdate(String publicId) {
        if (publicId == null || !publicId.matches("[0-9A-HJKMNP-TV-Z]{26}")) {
            throw notFound();
        }
        RecruitmentTaskEntity task = mapper.findByPublicIdForUpdate(publicId);
        if (task == null) throw notFound();
        return task;
    }

    RecruitmentTaskEntity requireActive(String publicId) {
        RecruitmentTaskEntity task = requireByPublicId(publicId);
        LocalDateTime now = now();
        if (task.getStatus() != RecruitmentTaskStatus.PUBLISHED
                || task.getStartsAt().isAfter(now)
                || !task.getEndsAt().isAfter(now)) {
            throw conflict("当前招募任务不在开放时间内");
        }
        return task;
    }

    /**
     * A locking read serializes draft creation with published-task deadline
     * changes and closure, preventing a concurrently inserted draft from
     * retaining a stale expires_at value.
     */
    RecruitmentTaskEntity requireActiveForDraftCreation(String publicId) {
        RecruitmentTaskEntity task = requireByPublicIdForUpdate(publicId);
        LocalDateTime now = now();
        if (task.getStatus() != RecruitmentTaskStatus.PUBLISHED
                || task.getStartsAt().isAfter(now)
                || !task.getEndsAt().isAfter(now)) {
            throw conflict("当前招募任务不在开放时间内");
        }
        return task;
    }

    PublicTaskView toPublicView(RecruitmentTaskEntity task) {
        return new PublicTaskView(task.getPublicId(), task.getTitle(), task.getIntroMarkdown(),
                schemaValidator.readSchema(task.getFormSchemaJson()), task.getStudentIdLabel(),
                task.getStudentIdHelp(), task.getUploadLabel(), task.getUploadHelp(),
                Boolean.TRUE.equals(task.getUploadRequired()), task.getStartsAt(), task.getEndsAt(),
                new UploadLimitsView(uploadProperties.maxImageCount(),
                        uploadProperties.maxImageBytes(), uploadProperties.maxArchiveBytes()));
    }

    private TaskView toView(RecruitmentTaskEntity task) {
        return new TaskView(task.getId(), task.getPublicId(), task.getTitle(), task.getIntroMarkdown(),
                schemaValidator.readSchema(task.getFormSchemaJson()), task.getStudentIdLabel(),
                task.getStudentIdHelp(), task.getUploadLabel(), task.getUploadHelp(),
                Boolean.TRUE.equals(task.getUploadRequired()), task.getStartsAt(), task.getEndsAt(),
                task.getStatus(), task.getCreatedBy(), task.getCreatorDisplayName(),
                task.getPublishedBy(), task.getPublishedAt(), task.getClosedBy(), task.getClosedAt(),
                task.getApplicationCount() == null ? 0 : task.getApplicationCount(),
                task.getVersion(), task.getCreatedAt(), task.getUpdatedAt());
    }

    private ValidatedCommand validate(TaskCommand command) {
        if (command == null) throw validation("招募任务内容不能为空");
        String title = cleanRequired(command.title(), 200, "任务标题");
        String intro = cleanOptional(command.introMarkdown(), 5_000, "任务介绍");
        RecruitmentFormSchema schema = schemaValidator.validate(command.formSchema());
        String schemaJson = schemaValidator.schemaJson(schema);
        String studentIdLabel = cleanRequired(command.studentIdLabel(), 100, "学号项标题");
        String studentIdHelp = cleanOptional(command.studentIdHelp(), 500, "学号项提示");
        String uploadLabel = cleanRequired(command.uploadLabel(), 100, "附件项标题");
        String uploadHelp = cleanOptional(command.uploadHelp(), 500, "附件项提示");
        if (command.startsAt() == null || command.endsAt() == null
                || !command.startsAt().isBefore(command.endsAt())) {
            throw validation("招募开始时间必须早于结束时间");
        }
        if (!command.endsAt().isAfter(now())) {
            throw validation("招募结束时间必须晚于当前时间");
        }
        return new ValidatedCommand(title, intro, schemaJson, studentIdLabel, studentIdHelp,
                uploadLabel, uploadHelp, command.uploadRequired(), command.startsAt(), command.endsAt());
    }

    private void apply(RecruitmentTaskEntity task, ValidatedCommand input) {
        task.setTitle(input.title());
        task.setIntroMarkdown(input.introMarkdown());
        task.setFormSchemaJson(input.formSchemaJson());
        task.setStudentIdLabel(input.studentIdLabel());
        task.setStudentIdHelp(input.studentIdHelp());
        task.setUploadLabel(input.uploadLabel());
        task.setUploadHelp(input.uploadHelp());
        task.setUploadRequired(input.uploadRequired());
        task.setStartsAt(input.startsAt());
        task.setEndsAt(input.endsAt());
    }

    private boolean formConfigurationChanged(RecruitmentTaskEntity task, ValidatedCommand input) {
        String currentSchemaJson = schemaValidator.schemaJson(
                schemaValidator.readSchema(task.getFormSchemaJson()));
        return !currentSchemaJson.equals(input.formSchemaJson())
                || !task.getStudentIdLabel().equals(input.studentIdLabel())
                || !equal(task.getStudentIdHelp(), input.studentIdHelp())
                || !task.getUploadLabel().equals(input.uploadLabel())
                || !equal(task.getUploadHelp(), input.uploadHelp())
                || Boolean.TRUE.equals(task.getUploadRequired()) != input.uploadRequired();
    }

    private void updateChecked(RecruitmentTaskEntity task) {
        if (mapper.updateById(task) != 1) {
            throw conflict("招募任务已被其他操作修改，请刷新后重试");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(recruitmentClock);
    }

    private static int requireVersion(int version) {
        if (version < 1) throw validation("版本号不合法");
        return version;
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String cleanRequired(String value, int max, String field) {
        String clean = cleanOptional(value, max, field);
        if (clean == null) throw validation(field + "不能为空");
        return clean;
    }

    private static String cleanOptional(String value, int max, String field) {
        if (value == null) return null;
        String clean = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
        if (clean.codePointCount(0, clean.length()) > max) {
            throw validation(field + "最多 " + max + " 个字符");
        }
        return clean.isEmpty() ? null : clean;
    }

    private static void requirePermission(AuthenticatedUser user, PermissionCode permission) {
        if (user == null || !user.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该招募任务操作");
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "招募任务不存在");
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, message);
    }

    public record TaskCommand(String title,
                              String introMarkdown,
                              RecruitmentFormSchema formSchema,
                              String studentIdLabel,
                              String studentIdHelp,
                              String uploadLabel,
                              String uploadHelp,
                              boolean uploadRequired,
                              LocalDateTime startsAt,
                              LocalDateTime endsAt) {
    }

    public record PublicTaskView(String publicId,
                                 String title,
                                 String introMarkdown,
                                 RecruitmentFormSchema formSchema,
                                 String studentIdLabel,
                                 String studentIdHelp,
                                 String uploadLabel,
                                 String uploadHelp,
                                 boolean uploadRequired,
                                 LocalDateTime startsAt,
                                 LocalDateTime endsAt,
                                 UploadLimitsView uploadLimits) {
    }

    /**
     * The quota the public application page must state and pre-check against. Served with the task so
     * the browser cannot drift from what the server actually enforces — the copy
     * and the constants used to be maintained in two places and disagreed.
     */
    public record UploadLimitsView(int maxImageCount, long maxImageBytes,
                                   long maxArchiveBytes) {
    }

    public record TaskView(Long id,
                           String publicId,
                           String title,
                           String introMarkdown,
                           RecruitmentFormSchema formSchema,
                           String studentIdLabel,
                           String studentIdHelp,
                           String uploadLabel,
                           String uploadHelp,
                           boolean uploadRequired,
                           LocalDateTime startsAt,
                           LocalDateTime endsAt,
                           RecruitmentTaskStatus status,
                           Long createdBy,
                           String creatorDisplayName,
                           Long publishedBy,
                           LocalDateTime publishedAt,
                           Long closedBy,
                           LocalDateTime closedAt,
                           long applicationCount,
                           Integer version,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
    }

    private record ValidatedCommand(String title,
                                    String introMarkdown,
                                    String formSchemaJson,
                                    String studentIdLabel,
                                    String studentIdHelp,
                                    String uploadLabel,
                                    String uploadHelp,
                                    boolean uploadRequired,
                                    LocalDateTime startsAt,
                                    LocalDateTime endsAt) {
    }
}

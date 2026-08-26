package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.permission.PermissionCode;
import cn.photolib.recruitment.mapper.RecruitmentApplicationMapper;
import cn.photolib.recruitment.model.RecruitmentApplicationEntity;
import cn.photolib.recruitment.model.RecruitmentDraftEntity;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.recruitment.model.RecruitmentTaskEntity;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RecruitmentApplicationService {
    /** 同步导出的行数上限；超过就要求先缩小筛选范围，而不是截断结果。 */
    static final int EXPORT_ROW_LIMIT = 10_000;

    private final RecruitmentApplicationMapper mapper;
    private final RecruitmentDraftService draftService;
    private final RecruitmentTaskService taskService;
    private final RecruitmentFormSchemaValidator schemaValidator;
    private final RecruitmentAttachmentReader attachmentReader;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final Clock recruitmentClock;

    @Transactional
    public SubmissionReceipt submit(String publicId, String draftId, String rawToken,
                                    String studentId, Map<String, Object> answers) {
        RecruitmentDraftEntity draft = draftService.requireWritableForMutation(
                publicId, draftId, rawToken);
        RecruitmentTaskEntity task = taskService.requireByPublicId(publicId);
        RecruitmentStudentId.Normalized normalizedStudentId = RecruitmentStudentId.normalize(studentId);
        // This binds the submitted identifier to the capability's original input.
        // It enforces per-task input uniqueness; it is not proof of the applicant's
        // real-world identity and must not be presented as identity authentication.
        if (!draft.getNormalizedStudentId().equals(normalizedStudentId.value())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "提交学号必须与创建申请草稿时填写的学号一致");
        }
        draftService.requireNotSubmitted(task.getId(), normalizedStudentId.value());

        RecruitmentFormSchema schema = schemaValidator.readSchema(task.getFormSchemaJson());
        Map<String, Object> checkedAnswers = schemaValidator.validateAnswers(schema, answers);
        RecruitmentAttachmentReader.DraftAttachmentState attachmentState =
                attachmentReader.stateForDraft(draft.getId());
        if (attachmentState == null) {
            throw new IllegalStateException("招募附件读取器返回了空状态");
        }
        if (attachmentState.inProgress()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "附件仍在上传或处理中，请完成后再提交");
        }
        if (Boolean.TRUE.equals(task.getUploadRequired()) && attachmentState.attachments().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请至少上传一张图片");
        }

        LocalDateTime now = now();
        RecruitmentApplicationEntity application = new RecruitmentApplicationEntity();
        application.setId(PublicId.next());
        application.setTaskId(task.getId());
        application.setDraftId(draft.getId());
        application.setStudentId(normalizedStudentId.display());
        application.setNormalizedStudentId(normalizedStudentId.value());
        application.setAnswersJson(schemaValidator.answersJson(checkedAnswers));
        application.setFormSchemaJson(schemaValidator.schemaJson(schema));
        application.setSubmittedAt(now);
        application.setCreatedAt(now);
        try {
            mapper.insert(application);
        } catch (DuplicateKeyException exception) {
            throw RecruitmentDraftService.duplicateStudent();
        }
        if (draftService.markSubmitted(draft.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "招募申请草稿已提交或已失效");
        }
        return new SubmissionReceipt(application.getId(), application.getSubmittedAt());
    }

    public PageResponse<ApplicationSummary> list(long taskId, int page, int pageSize,
                                                  String studentIdFragment, AuthenticatedUser user) {
        requireView(user);
        RecruitmentTaskEntity task = taskService.requireTask(taskId);
        String normalizedFragment = RecruitmentStudentId.normalizeSearchFragment(studentIdFragment);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        long total = mapper.countByTask(task.getId(), normalizedFragment);
        List<ApplicationSummary> items = mapper.findByTask(task.getId(), normalizedFragment, safePageSize,
                        (long) (safePage - 1) * safePageSize).stream()
                .map(application -> new ApplicationSummary(application.getId(), application.getTaskId(),
                        application.getStudentId(), application.getSubmittedAt()))
                .toList();
        return new PageResponse<>(items, safePage, safePageSize, total,
                total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
    }

    /**
     * 导出当前筛选条件下的报名，供部内打印后带到面试现场核对。
     * 筛选口径必须与 {@link #list} 完全一致，否则页面上看到的和导出的会对不上。
     */
    public ApplicationExport export(long taskId, String studentIdFragment, AuthenticatedUser user) {
        requireView(user);
        RecruitmentTaskEntity task = taskService.requireTask(taskId);
        String normalizedFragment = RecruitmentStudentId.normalizeSearchFragment(studentIdFragment);
        // 同步生成、整表进内存，所以先数一遍：宁可让用户按学号缩小范围，
        // 也不要悄悄只导出前一部分，让面试现场少叫几个人。
        long total = mapper.countByTask(task.getId(), normalizedFragment);
        if (total > EXPORT_ROW_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "一次最多导出 " + EXPORT_ROW_LIMIT + " 条报名，请先按学号缩小范围再导出");
        }
        List<RecruitmentApplicationEntity> applications =
                mapper.findByTask(task.getId(), normalizedFragment, EXPORT_ROW_LIMIT, 0);
        Map<String, Integer> attachmentCounts = attachmentReader.attachmentCountsByDraft(
                applications.stream().map(RecruitmentApplicationEntity::getDraftId).toList());
        List<RecruitmentApplicationExport.Entry> entries = applications.stream()
                .map(application -> new RecruitmentApplicationExport.Entry(
                        application.getStudentId(), application.getSubmittedAt(),
                        attachmentCounts.getOrDefault(application.getDraftId(), 0),
                        schemaValidator.readSchema(application.getFormSchemaJson()),
                        schemaValidator.readAnswers(application.getAnswersJson())))
                .toList();
        byte[] content = RecruitmentApplicationExport.workbook(
                schemaValidator.readSchema(task.getFormSchemaJson()), entries);
        String fileName = RecruitmentApplicationExport.fileName(task.getTitle(),
                LocalDate.now(recruitmentClock));
        return new ApplicationExport(fileName, content);
    }

    public ApplicationDetail get(String id, AuthenticatedUser user) {
        requireView(user);
        RecruitmentApplicationEntity application = id == null ? null : mapper.selectById(id);
        if (application == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "招募申请不存在");
        }
        RecruitmentTaskEntity task = taskService.requireTask(application.getTaskId());
        RecruitmentFormSchema schema = schemaValidator.readSchema(application.getFormSchemaJson());
        Map<String, Object> answers = schemaValidator.readAnswers(application.getAnswersJson());
        RecruitmentAttachmentReader.DraftAttachmentState state =
                attachmentReader.stateForDraft(application.getDraftId());
        List<RecruitmentAttachmentReader.Attachment> attachments = state == null
                ? List.of() : state.attachments();
        List<AttachmentView> attachmentViews = attachments.stream().map(this::toAttachmentView).toList();
        String detailsMarkdown = detailsMarkdown(task.getTitle(), application.getStudentId(),
                application.getSubmittedAt(), schema, answers, attachments);
        return new ApplicationDetail(application.getId(), application.getTaskId(), task.getTitle(),
                application.getStudentId(), application.getSubmittedAt(), detailsMarkdown, attachmentViews);
    }

    String detailsMarkdown(String taskTitle, String studentId, LocalDateTime submittedAt,
                           RecruitmentFormSchema schema, Map<String, Object> answers,
                           List<RecruitmentAttachmentReader.Attachment> attachments) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(escapeMarkdown(taskTitle)).append("\n\n")
                .append("- **学号：** ").append(escapeMarkdown(studentId)).append("\n")
                .append("- **提交时间：** ").append(escapeMarkdown(String.valueOf(submittedAt))).append("\n");
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            markdown.append("\n## ").append(escapeMarkdown(field.label())).append("\n\n")
                    .append(escapeMultiline(answerText(answers.get(field.id())))).append("\n");
        }
        markdown.append("\n## 附件\n\n");
        if (attachments == null || attachments.isEmpty()) {
            markdown.append("（未上传附件）\n");
        } else {
            for (RecruitmentAttachmentReader.Attachment attachment : attachments) {
                markdown.append("- ").append(escapeMarkdown(attachment.name()))
                        .append("（").append(attachment.size()).append(" 字节）\n");
            }
        }
        return markdown.toString();
    }

    static String escapeMarkdown(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r') {
                escaped.append(' ');
            } else if (codePoint < 128 && "\\`*_{}[]()<>#+-.!|>~".indexOf(codePoint) >= 0) {
                escaped.append('\\').appendCodePoint(codePoint);
            } else {
                escaped.appendCodePoint(codePoint);
            }
        }
        return escaped.toString();
    }

    private static String escapeMultiline(String value) {
        if (value == null || value.isBlank()) return "（未填写）";
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> escaped = new ArrayList<>(lines.length);
        for (String line : lines) escaped.add(escapeMarkdown(line));
        return String.join("  \n", escaped);
    }

    private static String answerText(Object answer) {
        if (answer == null) return "（未填写）";
        if (answer instanceof List<?> values) {
            return String.join("、", values.stream().map(String::valueOf).toList());
        }
        return String.valueOf(answer);
    }

    private AttachmentView toAttachmentView(RecruitmentAttachmentReader.Attachment attachment) {
        ObjectStorageService.SignedUrl preview = attachment.contentType() != null
                && attachment.contentType().startsWith("image/")
                ? storage.presignGet(attachment.objectKey(), null, storageProperties.downloadUrlTtl())
                : null;
        ObjectStorageService.SignedUrl download = storage.presignGet(
                attachment.objectKey(), attachment.name(), storageProperties.downloadUrlTtl());
        return new AttachmentView(attachment.id(), attachment.name(), attachment.contentType(),
                attachment.size(), attachment.sha256(),
                preview == null ? null : preview.url().toString(), download.url().toString(),
                download.expiresAt());
    }

    private void requireView(AuthenticatedUser user) {
        if (user == null || !user.hasPermission(PermissionCode.RECRUITMENT_VIEW)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看招募申请");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(recruitmentClock);
    }

    public record SubmissionReceipt(String applicationId, LocalDateTime submittedAt) {
    }

    public record ApplicationSummary(String id, Long taskId, String studentId,
                                     LocalDateTime submittedAt) {
    }

    /** 导出结果：文件名（含招募标题和导出日期）和 XLSX 字节流。 */
    public record ApplicationExport(String fileName, byte[] content) {
    }

    public record ApplicationDetail(String id, Long taskId, String taskTitle,
                                    String studentId, LocalDateTime submittedAt,
                                    String detailsMarkdown, List<AttachmentView> attachments) {
    }

    public record AttachmentView(long id, String fileName, String contentType, long size,
                                 String sha256, String previewUrl, String downloadUrl,
                                 java.time.Instant expiresAt) {
    }
}

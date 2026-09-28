package cn.photolib.form;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import cn.photolib.common.util.PublicId;
import cn.photolib.recruitment.RecruitmentFormSchemaValidator;
import cn.photolib.recruitment.RecruitmentTimeConfig;
import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 招募和问卷共用的「上传文件」题目。
 *
 * <p>流程和招募作品上传一样是浏览器直传：先要一张预签名 PUT 票据（落一行 PENDING），
 * 浏览器把文件 PUT 到对象存储，提交答卷时答案里只带这些文件的 id。提交事务里
 * {@link #attachAnswers} 逐个核对 id 属于这份答卷的这道题、对象真的存在且大小一致，
 * 再把它们改成 ATTACHED，并把答案换成「id + 文件名 + 类型 + 大小」存进答卷——
 * 这样详情、导出都不用再回表就能显示文件名。
 *
 * <p>文件不压缩、不转格式、不限类型；下载一律以附件形式签名（带文件名的
 * Content-Disposition），只有常见位图才额外签一个内联预览地址，避免上传的 HTML/SVG
 * 在我们的域名下被当成页面打开。
 */
@Service
@RequiredArgsConstructor
public class FormFileService {
    /** 一份没提交的答卷最多同时挂多少个待提交文件，防止匿名草稿被当成网盘。 */
    static final int MAX_PENDING_PER_OWNER = 60;
    private static final Pattern CONTENT_TYPE = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final Pattern EXTENSION = Pattern.compile("\\.[a-z0-9]{1,10}");
    private static final Set<String> PREVIEWABLE = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final FormFileUploadMapper mapper;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final Clock recruitmentClock;
    private final UploadLimitService uploadLimits;

    /** 这份答卷是谁的：招募草稿（匿名），或者某个成员在某份问卷里的答卷。 */
    public record Owner(FormFileOwnerType type, String ref, Long userId) {
        public Owner {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(ref, "ref");
        }

        public static Owner recruitmentDraft(String draftId) {
            return new Owner(FormFileOwnerType.RECRUITMENT, draftId, null);
        }

        public static Owner surveyRespondent(long surveyId, long userId) {
            return new Owner(FormFileOwnerType.SURVEY, String.valueOf(surveyId), userId);
        }
    }

    public record TicketRequest(String fieldId, String fileName, String contentType, Long size) {
    }

    public record UploadTicket(String fileId, String fileName, String uploadUrl, String method,
                               String contentType, Instant expiresAt) {
    }

    public record FileView(String id, String fieldId, String fileName, String contentType, long size,
                           String previewUrl, String downloadUrl, Instant expiresAt) {
    }

    /**
     * 为一道「上传文件」题发一张直传票据。
     *
     * @param notAfter 答卷自身的截止时间（招募草稿过期、问卷截止），签名不能活得比它长；可以为 null
     */
    @Transactional
    public UploadTicket createTicket(Owner owner, RecruitmentFormSchema schema, TicketRequest request,
                                     LocalDateTime notAfter) {
        if (request == null) throw validation("缺少文件信息");
        RecruitmentFormSchema.Field field = requireFileField(schema, request.fieldId());
        String fileName = cleanFileName(request.fileName());
        long size = request.size() == null ? 0 : request.size();
        if (size <= 0) throw validation("「" + fileName + "」是空文件");
        // 单个文件的上限由管理员在「上传限额」里设（FORM_FILE_MAX_BYTES）。
        if (size > uploadLimits.value(UploadLimit.FORM_FILE_MAX_BYTES)) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "「" + fileName + "」超过了 " + uploadLimits.describe(UploadLimit.FORM_FILE_MAX_BYTES)
                            + "，请压缩后再传");
        }
        if (mapper.countPending(owner.type(), owner.ref()) >= MAX_PENDING_PER_OWNER) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "这份答卷上传的文件太多了，请先提交或刷新页面后再试");
        }
        String contentType = normalizeContentType(request.contentType());
        LocalDateTime now = now();
        Duration ttl = uploadTtl(now, notAfter);
        String id = PublicId.next();
        String objectKey = objectKeyPrefix(owner) + UUID.randomUUID() + extension(fileName);
        ObjectStorageService.SignedUrl signed = storage.presignPut(objectKey, contentType, ttl);

        FormFileUploadEntity upload = new FormFileUploadEntity();
        upload.setId(id);
        upload.setOwnerType(owner.type());
        upload.setOwnerRef(owner.ref());
        upload.setUploaderUserId(owner.userId());
        upload.setFieldId(field.id());
        upload.setFileName(fileName);
        upload.setContentType(contentType);
        upload.setSize(size);
        upload.setObjectKey(objectKey);
        upload.setStatus(FormFileStatus.PENDING);
        upload.setUploadUrlExpiresAt(LocalDateTime.ofInstant(signed.expiresAt(), RecruitmentTimeConfig.ZONE));
        upload.setCreatedAt(now);
        mapper.insert(upload);
        return new UploadTicket(id, fileName, signed.url().toString(), signed.method(), contentType,
                signed.expiresAt());
    }

    /**
     * 把答案里「上传文件」题的 id 列表换成已核对的文件描述，并把这些文件标记为已引用。
     * 必须在提交答卷的同一个事务里调用：提交失败回滚时文件会回到 PENDING，仍可再次提交。
     */
    @Transactional
    public Map<String, Object> attachAnswers(Owner owner, RecruitmentFormSchema schema,
                                             Map<String, Object> answers) {
        Map<String, Object> result = new LinkedHashMap<>(answers);
        LocalDateTime now = now();
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            if (field.type() != RecruitmentFieldType.FILE_UPLOAD) continue;
            Object value = answers.get(field.id());
            if (!(value instanceof List<?> ids) || ids.isEmpty()) continue;
            List<Map<String, Object>> files = new ArrayList<>(ids.size());
            for (Object rawId : ids) {
                FormFileUploadEntity upload = rawId instanceof String id ? mapper.findByIdForUpdate(id) : null;
                if (upload == null || upload.getOwnerType() != owner.type()
                        || !owner.ref().equals(upload.getOwnerRef())
                        || !Objects.equals(owner.userId(), upload.getUploaderUserId())
                        || !field.id().equals(upload.getFieldId())) {
                    throw validation("“" + field.label() + "”里有文件找不到了，请重新上传");
                }
                if (upload.getStatus() != FormFileStatus.PENDING) {
                    throw conflict("“" + field.label() + "”里的「" + upload.getFileName() + "」已经提交过了");
                }
                ObjectStorageService.ObjectInfo stored = storage.find(upload.getObjectKey()).orElse(null);
                if (stored == null || stored.size() != upload.getSize()) {
                    throw conflict("「" + upload.getFileName() + "」还没有上传完整，请删掉它重新上传");
                }
                if (mapper.attach(upload.getId(), now) != 1) {
                    throw conflict("「" + upload.getFileName() + "」已经提交过了");
                }
                files.add(describe(upload));
            }
            result.put(field.id(), List.copyOf(files));
        }
        return result;
    }

    /** 答卷里所有「上传文件」题引用的文件 id（按题目顺序），供详情页签下载地址。 */
    public static List<String> fileIds(RecruitmentFormSchema schema, Map<String, Object> answers) {
        Set<String> ids = new LinkedHashSet<>();
        if (schema == null || answers == null) return List.of();
        for (RecruitmentFormSchema.Field field : schema.fields()) {
            if (field.type() != RecruitmentFieldType.FILE_UPLOAD) continue;
            if (!(answers.get(field.id()) instanceof List<?> values)) continue;
            for (Object value : values) {
                if (value instanceof Map<?, ?> file && file.get("id") instanceof String id) ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 为已提交答卷里的文件签下载地址。只签 ATTACHED 且属于 {@code owner} 的文件：
     * id 来自答卷 JSON，但仍然回表核对，不让一份答卷借另一份的文件 id 读到别人的附件。
     */
    public List<FileView> views(Owner owner, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<String, FormFileUploadEntity> rows = new LinkedHashMap<>();
        for (FormFileUploadEntity row : mapper.selectBatchIds(List.copyOf(new LinkedHashSet<>(ids)))) {
            rows.put(row.getId(), row);
        }
        List<FileView> views = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            FormFileUploadEntity row = rows.get(id);
            if (row == null || row.getStatus() != FormFileStatus.ATTACHED
                    || row.getOwnerType() != owner.type() || !owner.ref().equals(row.getOwnerRef())
                    || !Objects.equals(owner.userId(), row.getUploaderUserId())) continue;
            Duration ttl = storageProperties.downloadUrlTtl();
            ObjectStorageService.SignedUrl download = storage.presignGet(row.getObjectKey(), row.getFileName(), ttl);
            ObjectStorageService.SignedUrl preview = PREVIEWABLE.contains(row.getContentType())
                    ? storage.presignGet(row.getObjectKey(), null, ttl) : null;
            views.add(new FileView(row.getId(), row.getFieldId(), row.getFileName(), row.getContentType(),
                    row.getSize(), preview == null ? null : preview.url().toString(),
                    download.url().toString(), download.expiresAt()));
        }
        return List.copyOf(views);
    }

    private static Map<String, Object> describe(FormFileUploadEntity upload) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", upload.getId());
        file.put("fileName", upload.getFileName());
        file.put("contentType", upload.getContentType());
        file.put("size", upload.getSize());
        return file;
    }

    private static RecruitmentFormSchema.Field requireFileField(RecruitmentFormSchema schema, String fieldId) {
        if (schema != null && fieldId != null) {
            for (RecruitmentFormSchema.Field field : schema.fields()) {
                if (field.id().equals(fieldId) && field.type() == RecruitmentFieldType.FILE_UPLOAD) return field;
            }
        }
        throw validation("这道题不能上传文件");
    }

    private static String cleanFileName(String fileName) {
        String cleaned;
        try {
            cleaned = ImageUploadPolicy.safeDisplayFileName(fileName);
        } catch (IllegalArgumentException exception) {
            throw validation("文件名不能为空");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > 255) {
            throw validation("文件名太长了，请改短一点（255 字以内）");
        }
        return cleaned;
    }

    /**
     * 浏览器给的 MIME 类型经常是空的或者带参数；签名和 PUT 的 Content-Type 必须完全一致，
     * 所以由服务端定下一个规整的值写进票据，浏览器照着票据上传。
     */
    static String normalizeContentType(String contentType) {
        if (contentType == null) return DEFAULT_CONTENT_TYPE;
        String value = contentType.trim().toLowerCase(Locale.ROOT);
        int parameters = value.indexOf(';');
        if (parameters >= 0) value = value.substring(0, parameters).trim();
        return CONTENT_TYPE.matcher(value).matches() ? value : DEFAULT_CONTENT_TYPE;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return EXTENSION.matcher(extension).matches() ? extension : "";
    }

    private static String objectKeyPrefix(Owner owner) {
        return switch (owner.type()) {
            case RECRUITMENT -> "forms/recruitments/" + owner.ref() + "/";
            case SURVEY -> "forms/surveys/" + owner.ref() + "/" + owner.userId() + "/";
        };
    }

    private Duration uploadTtl(LocalDateTime now, LocalDateTime notAfter) {
        Duration configured = storageProperties.uploadUrlTtl();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            throw new IllegalStateException("上传签名有效期必须大于零");
        }
        Duration ttl = configured.compareTo(RecruitmentTimeConfig.MAX_UPLOAD_URL_TTL) > 0
                ? RecruitmentTimeConfig.MAX_UPLOAD_URL_TTL : configured;
        if (notAfter == null) return ttl;
        Duration remaining = Duration.between(now, notAfter);
        if (remaining.isZero() || remaining.isNegative()) throw conflict("已经过了截止时间，不能再上传");
        return remaining.compareTo(ttl) < 0 ? remaining : ttl;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(recruitmentClock);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, message);
    }
}

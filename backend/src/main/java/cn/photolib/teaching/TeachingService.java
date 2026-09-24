package cn.photolib.teaching;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.PdfUpload;
import cn.photolib.common.util.PublicId;
import cn.photolib.notification.NotificationService;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.teaching.mapper.TeachingMaterialMapper;
import cn.photolib.teaching.model.TeachingMaterialEntity;
import cn.photolib.teaching.model.TeachingMaterialFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 教学资料。两条访问路径的规则分开：
 *
 * <ul>
 *   <li><b>读取/下载</b>由控制器上的 {@code PHOTO_VIEW} 把门：凡是图库成员都能看、能下。</li>
 *   <li><b>上传/替换/编辑/删除</b>由控制器上的 {@code TEACHING_MANAGE} 把门：默认只有管理员和部长。</li>
 * </ul>
 *
 * <p>首版只有 PDF：上传走 {@link PdfUpload} 校验（只看文件头，不信声明的 Content-Type），
 * 存储格式列写成 {@link TeachingMaterialFormat#PDF}。Word/PPT 的枚举值先留着，
 * 以后放开时不用改表结构。</p>
 *
 * <p>上传即发布：没有草稿态，新建完成立刻对图库成员可见，并给所有 {@code PHOTO_VIEW} 账户
 * 发一条站内通知（见 {@link #notifyPublished}）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeachingService {
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_DESCRIPTION_CHARS = 1000;
    static final int MAX_CATEGORY_CHARS = 100;
    /** 人手维护的资料库上限，理由同 {@code DocService.MAX_NODES}：列表在内存里过滤，规模要可控。 */
    static final int MAX_MATERIALS = 1_000;
    /** 预览地址前缀，必须和 {@code TeachingReaderController} 的映射一致。 */
    public static final String FILE_URL_PREFIX = "/api/v1/teaching/materials/";
    public static final String FILE_URL_SUFFIX = "/file";
    public static final String DOWNLOAD_URL_SUFFIX = "/download";
    static final String EVENT_PUBLISHED = "TEACHING_PUBLISHED";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    /**
     * 「图库成员」的定义：启用、未删除，且权限组带 {@code PHOTO_VIEW}。
     * 作者选择、发布通知、作者校验三处都从这一份里取，避免三份 SQL 慢慢走样。
     */
    private static final String GALLERY_MEMBERS_FROM = """
            FROM app_user u
            JOIN permission_group pg
              ON pg.id = COALESCE(u.permission_group_id,
                  (SELECT legacy_pg.id FROM permission_group legacy_pg
                   WHERE legacy_pg.code = u.role))
            JOIN permission_group_permission p
              ON p.group_id = pg.id AND p.permission_code = 'PHOTO_VIEW'
            WHERE u.enabled = TRUE AND u.deleted = FALSE AND pg.deleted = FALSE
            """;

    private final TeachingMaterialMapper mapper;
    private final ObjectStorageService storage;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    // ------------------------------------------------------------------
    // 读取（图库成员）
    // ------------------------------------------------------------------

    /** 列表按上传时间倒序；分类与标题/简介关键字在内存里过滤（资料量用手维护的规模）。 */
    public List<Material> list(String category, String query) {
        String cleanCategory = category == null ? "" : category.trim();
        String cleanQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return mapper.findAll().stream()
                .filter(material -> cleanCategory.isEmpty()
                        || cleanCategory.equals(material.getCategory()))
                .filter(material -> cleanQuery.isEmpty()
                        || contains(material.getTitle(), cleanQuery)
                        || contains(material.getDescription(), cleanQuery))
                .map(this::toMaterial)
                .toList();
    }

    public Material get(String publicId) {
        return toMaterial(requireReadable(publicId));
    }

    public List<String> categories() {
        return mapper.distinctCategories();
    }

    /** 作者下拉的候选：全体图库成员。 */
    public List<AuthorOption> authorOptions() {
        return jdbc.sql("SELECT DISTINCT u.id, u.display_name " + GALLERY_MEMBERS_FROM
                        + " ORDER BY u.display_name ASC")
                .query((resultSet, rowNum) -> new AuthorOption(
                        resultSet.getLong("id"), resultSet.getString("display_name")))
                .list();
    }

    /** 预览与下载共用的定位：既要存在，也要真的有文件。 */
    public TeachingMaterialEntity requireReadable(String publicId) {
        TeachingMaterialEntity material = publicId == null ? null
                : mapper.findByPublicId(publicId.trim());
        if (material == null || !StringUtils.hasText(material.getObjectKey())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "教学资料不存在");
        }
        return material;
    }

    public InputStream open(TeachingMaterialEntity material) {
        return storage.open(material.getObjectKey());
    }

    /** 下载计数只在真正下载时 +1；预览读同一个对象，不计数。 */
    @Transactional
    public void recordDownload(long id) {
        mapper.incrementDownloadCount(id);
    }

    // ------------------------------------------------------------------
    // 管理（TEACHING_MANAGE）
    // ------------------------------------------------------------------

    @Transactional
    public Material create(String title, String description, String category, Long authorId,
                           MultipartFile file, AuthenticatedUser user) throws IOException {
        PdfUpload.validate(file);
        if (mapper.countAll() >= MAX_MATERIALS) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "教学资料数量已达上限（" + MAX_MATERIALS + "），请先清理不再需要的资料");
        }
        String cleanTitle = normalizeTitle(title);
        requireUniqueTitle(cleanTitle, null);
        TeachingMaterialEntity material = new TeachingMaterialEntity();
        material.setPublicId(PublicId.next());
        material.setTitle(cleanTitle);
        material.setDescription(normalizeDescription(description));
        material.setCategory(normalizeCategory(category));
        material.setAuthorId(requireGalleryMemberAuthor(authorId));
        material.setFormat(TeachingMaterialFormat.PDF);
        material.setObjectKey(objectKey(material.getPublicId()));
        material.setContentSize(file.getSize());
        material.setDownloadCount(0L);
        material.setCreatedBy(user.id());
        material.setUpdatedBy(user.id());
        mapper.insert(material);
        storePdf(material.getObjectKey(), file);
        notifyPublished(material);
        return get(material.getPublicId());
    }

    @Transactional
    public Material updateMetadata(long id, String title, String description, String category,
                                   Long authorId, int version, AuthenticatedUser user) {
        TeachingMaterialEntity material = requireMaterial(id);
        String cleanTitle = normalizeTitle(title);
        requireUniqueTitle(cleanTitle, id);
        requireUpdated(mapper.updateMetadata(id, cleanTitle, normalizeDescription(description),
                normalizeCategory(category), requireGalleryMemberAuthor(authorId), user.id(),
                version, LocalDateTime.now()));
        return get(material.getPublicId());
    }

    @Transactional
    public Material replaceFile(long id, MultipartFile file, int version, AuthenticatedUser user)
            throws IOException {
        PdfUpload.validate(file);
        TeachingMaterialEntity material = requireMaterial(id);
        String objectKey = objectKey(material.getPublicId());
        requireUpdated(mapper.updateFile(id, objectKey, file.getSize(),
                TeachingMaterialFormat.PDF.name(), user.id(), version, LocalDateTime.now()));
        storePdf(objectKey, file);
        return get(material.getPublicId());
    }

    @Transactional
    public void delete(long id, int version, AuthenticatedUser user) {
        requireMaterial(id);
        requireUpdated(mapper.softDelete(id, version, LocalDateTime.now()));
    }

    /** 重命名分类：把这一类资料批量改到新名字。返回受影响的资料数。 */
    @Transactional
    public int renameCategory(String from, String to) {
        String cleanFrom = normalizeCategory(from);
        String cleanTo = normalizeCategory(to);
        if (cleanFrom.equals(cleanTo)) return 0;
        return mapper.renameCategory(cleanFrom, cleanTo, LocalDateTime.now());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private TeachingMaterialEntity requireMaterial(long id) {
        TeachingMaterialEntity material = mapper.selectById(id);
        if (material == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "教学资料不存在");
        }
        return material;
    }

    /**
     * 作者必须是一位图库成员（持有 {@code PHOTO_VIEW} 的启用账户）。可选：不填就留空。
     */
    private Long requireGalleryMemberAuthor(Long authorId) {
        if (authorId == null) return null;
        Long found = jdbc.sql("SELECT u.id " + GALLERY_MEMBERS_FROM + " AND u.id = :id")
                .param("id", authorId).query(Long.class).optional().orElse(null);
        if (found == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "作者必须是图库成员");
        }
        return found;
    }

    /** 新建即发布：给所有图库成员发一条站内通知。 */
    private void notifyPublished(TeachingMaterialEntity material) {
        String subject = "新的教学资料：" + material.getTitle();
        String body = NotificationService.paragraphs(material.getTitle(), material.getCategory());
        galleryMemberIds().forEach(userId -> notifications.notifyUser(
                userId, EVENT_PUBLISHED, subject, body));
    }

    private List<Long> galleryMemberIds() {
        return jdbc.sql("SELECT DISTINCT u.id " + GALLERY_MEMBERS_FROM)
                .query(Long.class).list();
    }

    private void requireUniqueTitle(String title, Long excludeId) {
        if (mapper.countByTitle(title, excludeId) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "已经有同名的教学资料");
        }
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "教学资料已被其他操作修改，请刷新后重试");
        }
    }

    private String normalizeTitle(String title) {
        String cleaned = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "标题不能为空");
        }
        if (cleaned.length() > MAX_TITLE_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "标题不能超过 " + MAX_TITLE_CHARS + " 个字符");
        }
        return cleaned;
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) return null;
        String cleaned = description.trim();
        if (cleaned.length() > MAX_DESCRIPTION_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "简介不能超过 " + MAX_DESCRIPTION_CHARS + " 个字符");
        }
        return cleaned;
    }

    private String normalizeCategory(String category) {
        String cleaned = category == null ? "" : category.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分类不能为空");
        }
        if (cleaned.length() > MAX_CATEGORY_CHARS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "分类不能超过 " + MAX_CATEGORY_CHARS + " 个字符");
        }
        return cleaned;
    }

    private boolean contains(String value, String lowercaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    private String objectKey(String publicId) {
        return "teaching/" + publicId + "/document.pdf";
    }

    private void storePdf(String objectKey, MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            storage.put(objectKey, input, file.getSize(), PDF_CONTENT_TYPE);
        }
    }

    private Material toMaterial(TeachingMaterialEntity material) {
        TeachingMaterialFormat format = material.getFormat() == null
                ? TeachingMaterialFormat.PDF : material.getFormat();
        return new Material(
                material.getId(),
                material.getPublicId(),
                material.getTitle(),
                material.getDescription(),
                material.getCategory(),
                material.getAuthorId(),
                material.getAuthorDisplayName(),
                material.getUploaderDisplayName(),
                format.name(),
                material.getContentSize() == null ? 0L : material.getContentSize(),
                material.getDownloadCount() == null ? 0L : material.getDownloadCount(),
                material.getCreatedAt(),
                material.getUpdatedAt(),
                material.getVersion() == null ? 1 : material.getVersion(),
                FILE_URL_PREFIX + material.getPublicId() + FILE_URL_SUFFIX,
                FILE_URL_PREFIX + material.getPublicId() + DOWNLOAD_URL_SUFFIX);
    }

    /** 列表与详情共用的视图。fileUrl 供预览，downloadUrl 供计数下载。 */
    public record Material(long id, String publicId, String title, String description,
                           String category, Long authorId, String authorName, String uploaderName,
                           String format,
                           long size, long downloadCount, LocalDateTime createdAt,
                           LocalDateTime updatedAt, int version, String fileUrl,
                           String downloadUrl) {
    }

    /** 作者下拉的一个候选项。 */
    public record AuthorOption(long id, String displayName) {
    }
}

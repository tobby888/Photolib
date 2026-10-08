package cn.photolib.doc.file;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.util.LikeFilter;
import cn.photolib.common.util.PublicId;
import cn.photolib.doc.DocAudience;
import cn.photolib.doc.DocReader;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.permission.PermissionCode;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件库：文档中心里"传一个文件上去，指定谁能下载"的那一半。
 *
 * <p><b>三种身份，三条规则，不要混：</b></p>
 * <ul>
 *   <li><b>上传</b>要 {@code FILE_UPLOAD}；上传者之后还能改自己文件的下载范围、删掉它。</li>
 *   <li><b>管理别人的文件</b>要 {@code FILE_MANAGE}。</li>
 *   <li><b>下载</b>不要任何权限码：由上传者给每个文件指定——所有人（含未登录）/ 登录后 /
 *       指定权限组与成员。判定和文档共用 {@link DocAudience#allows}；上传者本人和
 *       {@code FILE_MANAGE} 永远能下载。</li>
 * </ul>
 *
 * <p><b>所有数字都来自管理员的上传限额</b>（文件库一组）：单个文件、每人存储空间、每人每天个数、
 * 上传 / 下载 QPS、每人每小时下载次数、匿名每日流量、下载链接有效期。见 {@link DocFileTraffic}。</p>
 *
 * <p><b>下载不经过应用</b>：签一个短命的直链（有效期由管理员定），浏览器直接去对象存储取。
 * 所以前端不受 axios 超时影响，大文件也不占应用带宽；代价是一条链接在有效期内可以被
 * 反复使用，这正是"下载链接有效期"那项限额存在的理由，也是匿名流量按"签发时的文件大小"
 * 记账的原因——偏保守，只会多算不会少算。</p>
 *
 * <p><b>存进对象存储的类型一律是 {@code application/octet-stream}</b>，下载一律是附件：
 * 本地存储的签名地址和站点同源，按上传者声明的 {@code text/html} 原样回吐就是存储型 XSS。</p>
 */
@Service
@RequiredArgsConstructor
public class DocFileService {
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_TITLE = 200;
    static final int MAX_FILE_NAME = 255;
    static final int MAX_DESCRIPTION = 1000;
    private static final String STORED_CONTENT_TYPE = "application/octet-stream";

    private final DocFileMapper mapper;
    private final DocAudience audience;
    private final ObjectStorageService storage;
    private final UploadLimitService limits;
    private final DocFileTraffic traffic;
    private final JdbcClient jdbc;

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 读者能下载的文件。未登录只看得到"所有人"那一档；列表条件和下载判定必须一致，
     * 见 {@link DocFileMapper} 的类注释。
     *
     * @param mine 只看自己上传的（"我的文件"），匿名时忽略
     */
    public PageResponse<FileView> list(DocReader reader, String keyword, boolean mine, int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int current = Math.max(1, page);
        String like = StringUtils.hasText(keyword)
                ? LikeFilter.escape(keyword.trim().toLowerCase(Locale.ROOT)) : null;
        Long uploaderId = mine && reader.authenticated() ? reader.userId() : null;
        boolean manager = reader.has(PermissionCode.FILE_MANAGE);
        long total = mapper.count(reader.userId(), reader.groupId(), manager, like, uploaderId);
        List<DocFileEntity> rows = mapper.page(reader.userId(), reader.groupId(), manager, like, uploaderId,
                (long) (current - 1) * size, size);
        // 名单只给能管这个文件的人看：谁在名单上本身也算内部信息。
        Map<Long, DocAudience.Grants> grants = audience.loadFor(DocAudience.ResourceType.FILE, rows.stream()
                .filter(row -> canManage(row, reader)).map(DocFileEntity::getId).toList());
        List<FileView> items = rows.stream()
                .map(row -> toView(row, grants.getOrDefault(row.getId(), DocAudience.Grants.NONE), reader))
                .toList();
        return new PageResponse<>(items, current, size, total, (total + size - 1) / size);
    }

    /** 上传者在页面上看的"已用多少、还能传几个"。 */
    public Usage usage(AuthenticatedUser user) {
        return new Usage(mapper.usedBytes(user.id()), limits.value(UploadLimit.FILE_USER_QUOTA_BYTES),
                mapper.uploadedSince(user.id(), traffic.startOfToday()),
                limits.count(UploadLimit.FILE_USER_DAILY_UPLOADS),
                limits.value(UploadLimit.FILE_MAX_BYTES));
    }

    // ------------------------------------------------------------------
    // 上传与管理
    // ------------------------------------------------------------------

    /**
     * 上传一个文件。
     *
     * <p>存储空间和每日个数在同一个事务里先锁住上传者的账号行再判：同一个人同时传两个文件时，
     * 不锁的话两边都会读到"还剩 100 MiB"然后一起超额。锁覆盖到写对象为止——
     * 顺序是"先插数据库、再写对象"，写对象失败整个事务回滚，不留下指向空对象的行。
     * 这把锁只挡同一个人的并发上传，账号行的其他写操作很少，等一次对象写入可以接受。</p>
     *
     * <p>文件不读进内存：{@link MultipartFile#getInputStream()} 原样交给对象存储。</p>
     */
    @Transactional
    public FileView upload(MultipartFile file, String title, String description, DocVisibility visibility,
                           Collection<Long> groupIds, Collection<Long> userIds,
                           AuthenticatedUser user) throws IOException {
        DocReader reader = DocReader.of(user);
        if (!reader.has(PermissionCode.FILE_UPLOAD)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有上传文件的权限");
        }
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择一个非空的文件");
        }
        long size = file.getSize();
        if (size > limits.value(UploadLimit.FILE_MAX_BYTES)) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "单个文件不能超过 " + limits.describe(UploadLimit.FILE_MAX_BYTES));
        }
        String fileName = normalizeFileName(file.getOriginalFilename());
        String cleanTitle = StringUtils.hasText(title) ? normalizeTitle(title) : normalizeTitle(stripExtension(fileName));
        String cleanDescription = normalizeDescription(description);
        DocVisibility target = visibility == null ? DocVisibility.MEMBERS : visibility;
        DocAudience.Grants grants = audience.normalize(target, groupIds, userIds);

        lockAccount(user.id());
        long today = mapper.uploadedSince(user.id(), traffic.startOfToday());
        if (today >= limits.count(UploadLimit.FILE_USER_DAILY_UPLOADS)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "今天已经上传了 " + today + " 个文件，每天最多 " + limits.describe(UploadLimit.FILE_USER_DAILY_UPLOADS));
        }
        long used = mapper.usedBytes(user.id());
        long quota = limits.value(UploadLimit.FILE_USER_QUOTA_BYTES);
        if (used + size > quota) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "存储空间不够了：已用 " + ImageUploadPolicy.describe(used) + "，每人最多 "
                            + ImageUploadPolicy.describe(quota) + "，请先删掉不用的文件");
        }

        LocalDateTime now = traffic.now();
        DocFileEntity entity = new DocFileEntity();
        entity.setPublicId(PublicId.next());
        entity.setTitle(cleanTitle);
        entity.setFileName(fileName);
        entity.setContentType(normalizeContentType(file.getContentType()));
        entity.setSize(size);
        entity.setObjectKey("doc-files/" + entity.getPublicId() + "/file");
        entity.setDescription(cleanDescription);
        entity.setVisibility(target);
        entity.setDownloadCount(0L);
        entity.setUploadedBy(user.id());
        entity.setUpdatedBy(user.id());
        // 显式用业务时钟（Asia/Shanghai）：每日个数按它的零点数，不能交给按 JVM 时区填值的处理器。
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        audience.replace(DocAudience.ResourceType.FILE, entity.getId(), grants);
        try (InputStream input = file.getInputStream()) {
            storage.put(entity.getObjectKey(), input, size, STORED_CONTENT_TYPE);
        }
        DocFileEntity saved = requireById(entity.getId());
        return toView(saved, grants, reader);
    }

    /** 改标题、说明和下载范围。上传者本人（仍有 FILE_UPLOAD）或 FILE_MANAGE。 */
    @Transactional
    public FileView update(long id, String title, String description, DocVisibility visibility,
                           Collection<Long> groupIds, Collection<Long> userIds, int version,
                           AuthenticatedUser user) {
        DocReader reader = DocReader.of(user);
        DocFileEntity file = requireById(id);
        requireManage(file, reader);
        if (visibility == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择谁能下载");
        DocAudience.Grants grants = audience.normalize(visibility, groupIds, userIds);
        if (mapper.updateMetadata(id, normalizeTitle(title), normalizeDescription(description), visibility.name(),
                user.id(), version, traffic.now()) != 1) {
            throw conflict();
        }
        audience.replace(DocAudience.ResourceType.FILE, id, grants);
        return toView(requireById(id), grants, reader);
    }

    /**
     * 软删除。对象存储里的文件刻意保留（同文档中心）：软删可撤销，回滚数据库后对象还在。
     * 删掉之后不再占用每人存储空间，但仍算在当天的上传个数里。
     */
    @Transactional
    public void delete(long id, int version, AuthenticatedUser user) {
        DocFileEntity file = requireById(id);
        requireManage(file, DocReader.of(user));
        if (mapper.softDelete(id, user.id(), version, traffic.now()) != 1) throw conflict();
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 签发一次下载。顺序是有意的：先按读者限次（最便宜，也挡住拿不存在的 id 刷接口），
     * 再判读者范围，最后扣流量、计数、签名——判不过的请求不消耗任何流量额度。
     * 扣流量和计数在同一个事务里，签名失败时一起回滚。
     */
    @Transactional
    public Download download(String publicId, DocReader reader, String remoteAddress) {
        traffic.requireReaderAllowance(reader, remoteAddress);
        DocFileEntity file = publicId == null ? null : mapper.findByPublicId(publicId.trim());
        if (file == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或已被删除");
        DocAudience.Grants grants = file.getVisibility() == DocVisibility.RESTRICTED
                ? audience.load(DocAudience.ResourceType.FILE, file.getId()) : DocAudience.Grants.NONE;
        if (!canDownload(file, grants, reader)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, reader.authenticated()
                    ? "这个文件只对指定的成员开放" : "这个文件需要登录后下载，请先登录");
        }
        traffic.recordDownload(reader, file.getSize());
        mapper.incrementDownloadCount(file.getId());
        ObjectStorageService.SignedUrl signed = storage.presignGet(file.getObjectKey(), file.getFileName(),
                Duration.ofSeconds(limits.value(UploadLimit.FILE_DOWNLOAD_LINK_TTL_SECONDS)));
        return new Download(signed.url().toString(), signed.expiresAt(), file.getFileName(), file.getSize());
    }

    /**
     * 能不能下载。和 {@link DocFileMapper} 列表条件是同一条规则的两种写法，改一处必须改另一处：
     * 上传者本人、FILE_MANAGE、或者在读者范围里。
     */
    static boolean canDownload(DocFileEntity file, DocAudience.Grants grants, DocReader reader) {
        if (reader.authenticated() && file.getUploadedBy() != null && file.getUploadedBy().equals(reader.userId())) {
            return true;
        }
        if (reader.has(PermissionCode.FILE_MANAGE)) return true;
        return DocAudience.allows(file.getVisibility(), grants, reader);
    }

    static boolean canManage(DocFileEntity file, DocReader reader) {
        if (!reader.authenticated()) return false;
        if (reader.has(PermissionCode.FILE_MANAGE)) return true;
        return reader.has(PermissionCode.FILE_UPLOAD) && reader.userId().equals(file.getUploadedBy());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void requireManage(DocFileEntity file, DocReader reader) {
        if (!canManage(file, reader)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能管理自己上传的文件");
        }
    }

    private DocFileEntity requireById(long id) {
        DocFileEntity file = mapper.selectById(id);
        if (file == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或已被删除");
        if (file.getUploadedBy() != null) {
            file.setUploaderDisplayName(jdbc.sql("SELECT display_name FROM app_user WHERE id=:id")
                    .param("id", file.getUploadedBy()).query(String.class).optional().orElse(null));
        }
        return file;
    }

    private void lockAccount(long userId) {
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR UPDATE").param("id", userId)
                .query(Long.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "账号不存在"));
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "文件已被其他操作修改，请刷新后重试");
    }

    /** 只留文件名本身：去掉路径（Windows 的也算）、控制字符和首尾空白，超长时保住扩展名。 */
    static String normalizeFileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "file";
        if (name.codePointCount(0, name.length()) > MAX_FILE_NAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            String stem = extension.isEmpty() ? name : name.substring(0, dot);
            int keep = MAX_FILE_NAME - extension.codePointCount(0, extension.length());
            name = stem.substring(0, stem.offsetByCodePoints(0, keep)) + extension;
        }
        return name;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String normalizeTitle(String title) {
        String cleaned = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件标题不能为空");
        if (cleaned.length() > MAX_TITLE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件标题不能超过 " + MAX_TITLE + " 个字符");
        }
        return cleaned;
    }

    private static String normalizeDescription(String description) {
        if (!StringUtils.hasText(description)) return null;
        String cleaned = description.trim();
        if (cleaned.length() > MAX_DESCRIPTION) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "说明不能超过 " + MAX_DESCRIPTION + " 个字符");
        }
        return cleaned;
    }

    /** 只用于展示的类型：不可信，长度和字符都收一收，缺省按二进制。 */
    private static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) return STORED_CONTENT_TYPE;
        String cleaned = contentType.replaceAll("\\p{Cntrl}", "").trim().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) return STORED_CONTENT_TYPE;
        return cleaned.length() > 150 ? cleaned.substring(0, 150) : cleaned;
    }

    private FileView toView(DocFileEntity file, DocAudience.Grants grants, DocReader reader) {
        boolean manageable = canManage(file, reader);
        DocAudience.Grants shown = manageable && file.getVisibility() == DocVisibility.RESTRICTED
                ? grants : DocAudience.Grants.NONE;
        return new FileView(file.getId(), file.getPublicId(), file.getTitle(), file.getFileName(),
                file.getContentType(), file.getSize() == null ? 0 : file.getSize(), file.getDescription(),
                file.getVisibility() == null ? DocVisibility.MEMBERS : file.getVisibility(),
                shown.groupIds(), shown.userIds(),
                file.getDownloadCount() == null ? 0 : file.getDownloadCount(),
                file.getUploadedBy(), file.getUploaderDisplayName(), file.getCreatedAt(), file.getUpdatedAt(),
                file.getVersion() == null ? 1 : file.getVersion(), manageable);
    }

    /** {@code readerGroupIds} / {@code readerUserIds} 只对能管理这个文件的人下发。 */
    public record FileView(Long id, String publicId, String title, String fileName, String contentType,
                           long size, String description, DocVisibility visibility,
                           Set<Long> readerGroupIds, Set<Long> readerUserIds, long downloadCount,
                           Long uploaderId, String uploaderDisplayName, LocalDateTime createdAt,
                           LocalDateTime updatedAt, int version, boolean canManage) {
    }

    public record Download(String downloadUrl, Instant expiresAt, String fileName, long size) {
    }

    public record Usage(long usedBytes, long quotaBytes, long uploadedToday, long dailyUploads,
                        long maxFileBytes) {
    }
}

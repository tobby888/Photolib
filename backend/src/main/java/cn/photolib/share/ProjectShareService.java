package cn.photolib.share;

import cn.photolib.adoption.AdoptionEntity;
import cn.photolib.adoption.AdoptionMapper;
import cn.photolib.adoption.AdoptionService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.LikeFilter;
import cn.photolib.common.util.PublicId;
import cn.photolib.permission.PermissionCode;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.share.mapper.ProjectShareLinkMapper;
import cn.photolib.share.mapper.ProjectShareSessionMapper;
import cn.photolib.statistics.ExportJobEntity;
import cn.photolib.statistics.ExportService;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 选题项目的对外分享链接。
 *
 * <p>三条不变量，改这个类之前先读一遍：</p>
 * <ol>
 *   <li><b>图片和被引都不复制。</b> 访客看到的图片是 {@code photo_project} 现查的，
 *       被引状态是 {@code adoption} 现查的，写入走 {@link AdoptionService#adoptOnBehalf}
 *       和 {@link AdoptionService#cancel}。所以同一个项目的多条链接彼此同步，也和
 *       站内看到的完全一致——这正是需求里"必须每个分享链接同步"的实现方式。</li>
 *   <li><b>会话不缓存权限。</b> 每次请求都按 {@code link_id} 重新读链接行，再判下载/
 *       被引开关、是否被撤销、是否过期。因此改权限和删链接立即生效，不必等会话过期。</li>
 *   <li><b>校区范围账号不能建链接。</b> 一条链接把整个项目相册（含其他校区的图片）
 *       交给站外的人，这超出了校区负责人自己能看到的范围。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ProjectShareService {
    /** 会话时长。访客多半是一次坐下来挑一批图，太短会让人反复输密码。 */
    static final Duration SESSION_TTL = Duration.ofHours(6);
    static final int MIN_PASSWORD_LENGTH = 6;
    static final int MAX_PASSWORD_LENGTH = 64;
    static final int MAX_LINKS_PER_PROJECT = 20;
    private static final int GENERATED_PASSWORD_LENGTH = 10;
    /** 去掉了 I/L/O/U 的 Crockford 字母表，念给人听、抄在纸上都不会认错。 */
    private static final char[] PASSWORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ProjectShareLinkMapper mapper;
    private final ProjectShareSessionMapper sessionMapper;
    private final ProjectService projectService;
    private final PhotoService photoService;
    private final PhotoMapper photoMapper;
    private final AdoptionService adoptionService;
    private final AdoptionMapper adoptionMapper;
    private final ExportService exportService;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final Clock clock;

    // ---------------------------------------------------------------- 管理端

    @Transactional
    public CreatedShareLink create(Long projectId, CreateCommand command, AuthenticatedUser user) {
        ProjectEntity project = requireManageable(projectId, user);
        long existing = mapper.selectCount(Wrappers.<ProjectShareLinkEntity>lambdaQuery()
                .eq(ProjectShareLinkEntity::getProjectId, project.getId()));
        if (existing >= MAX_LINKS_PER_PROJECT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "一个项目最多同时保留 " + MAX_LINKS_PER_PROJECT + " 条分享链接，请先删除不再使用的链接");
        }
        String password = StringUtils.hasText(command.password())
                ? validatedPassword(command.password()) : generatePassword();
        LocalDateTime expiresAt = validatedExpiry(command.expiresAt());

        ProjectShareLinkEntity link = new ProjectShareLinkEntity();
        link.setToken(PublicId.next());
        link.setProjectId(project.getId());
        link.setName(StringUtils.hasText(command.name()) ? command.name().trim() : null);
        link.setPasswordHash(passwordEncoder.encode(password));
        link.setAllowDownload(command.allowDownload());
        link.setAllowAdoption(command.allowAdoption());
        link.setExpiresAt(expiresAt);
        link.setViewCount(0L);
        link.setCreatedBy(user.id());
        mapper.insert(link);
        // 明文只在这里回一次，数据库里只有哈希。要"再看一眼密码"只能重置。
        return new CreatedShareLink(view(mapper.selectById(link.getId())), password);
    }

    public List<ShareLinkView> list(Long projectId, AuthenticatedUser user) {
        requireManageable(projectId, user);
        return mapper.selectList(Wrappers.<ProjectShareLinkEntity>lambdaQuery()
                        .eq(ProjectShareLinkEntity::getProjectId, projectId)
                        .orderByDesc(ProjectShareLinkEntity::getCreatedAt))
                .stream().map(this::view).toList();
    }

    @Transactional
    public ShareLinkView update(Long projectId, Long linkId, UpdateCommand command, AuthenticatedUser user) {
        requireManageable(projectId, user);
        ProjectShareLinkEntity link = requireLink(projectId, linkId);
        link.setName(StringUtils.hasText(command.name()) ? command.name().trim() : null);
        link.setAllowDownload(command.allowDownload());
        link.setAllowAdoption(command.allowAdoption());
        link.setExpiresAt(validatedExpiry(command.expiresAt()));
        link.setVersion(command.version());
        if (mapper.updateById(link) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "分享链接已被其他操作修改");
        }
        return view(mapper.selectById(linkId));
    }

    /**
     * 重置密码。旧密码换掉的同时**作废所有已发出的会话**：改密码的动机通常是
     * "这条链接传到了不该传的地方"，只换密码而让已经进来的人继续待着等于没改。
     */
    @Transactional
    public ResetPassword resetPassword(Long projectId, Long linkId, String requested,
                                        AuthenticatedUser user) {
        requireManageable(projectId, user);
        ProjectShareLinkEntity link = requireLink(projectId, linkId);
        String password = StringUtils.hasText(requested) ? validatedPassword(requested) : generatePassword();
        link.setPasswordHash(passwordEncoder.encode(password));
        mapper.updateById(link);
        sessionMapper.delete(Wrappers.<ProjectShareSessionEntity>lambdaQuery()
                .eq(ProjectShareSessionEntity::getLinkId, linkId));
        return new ResetPassword(view(mapper.selectById(linkId)), password);
    }

    @Transactional
    public void delete(Long projectId, Long linkId, AuthenticatedUser user) {
        requireManageable(projectId, user);
        ProjectShareLinkEntity link = requireLink(projectId, linkId);
        // 会话跟着硬删：链接是软删的（保留审计线索），而会话行留着没有任何用处。
        sessionMapper.delete(Wrappers.<ProjectShareSessionEntity>lambdaQuery()
                .eq(ProjectShareSessionEntity::getLinkId, link.getId()));
        mapper.deleteById(link.getId());
    }

    private ProjectEntity requireManageable(Long projectId, AuthenticatedUser user) {
        if (!user.hasPermission(PermissionCode.PROJECT_SHARE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理选题的分享链接");
        }
        if (user.isCampusScoped()) {
            // 分享链接会把整个项目相册（含其他校区的图片）交给站外的人，
            // 这超出了校区范围账号自己能看到的范围，不能由它发放。
            throw new BusinessException(ErrorCode.FORBIDDEN, "校区范围账号不能创建对外分享链接");
        }
        return projectService.getVisible(projectId, user);
    }

    private ProjectShareLinkEntity requireLink(Long projectId, Long linkId) {
        ProjectShareLinkEntity link = linkId == null ? null : mapper.selectById(linkId);
        if (link == null || !link.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "分享链接不存在");
        }
        return link;
    }

    // ---------------------------------------------------------------- 访客端

    /** 密码页只需要知道"这条链接还能用"，刻意不返回项目标题等任何项目信息。 */
    public LinkGreeting greet(String token) {
        requireUsable(token);
        return new LinkGreeting(true);
    }

    @Transactional
    public GuestSession openSession(String token, String password) {
        ProjectShareLinkEntity link = requireUsable(token);
        if (!StringUtils.hasText(password) || !passwordEncoder.matches(password, link.getPasswordHash())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "分享密码不正确");
        }
        String sessionToken = newSessionToken();
        LocalDateTime now = LocalDateTime.now(clock);
        ProjectShareSessionEntity session = new ProjectShareSessionEntity();
        session.setId(PublicId.next());
        session.setLinkId(link.getId());
        session.setTokenHash(hash(sessionToken));
        session.setExpiresAt(now.plus(SESSION_TTL));
        session.setCreatedAt(now);
        sessionMapper.insert(session);

        jdbc.sql("""
                UPDATE project_share_link SET view_count = view_count + 1, last_viewed_at = :now
                WHERE id = :id
                """).param("now", now).param("id", link.getId()).update();
        return new GuestSession(sessionToken, session.getExpiresAt(), access(link));
    }

    /**
     * 解析访客会话，返回**当前这一刻**的链接行。
     *
     * <p>每次请求都重读，是为了让"改权限/删链接"立刻生效，见类注释第 2 条。</p>
     */
    public ProjectShareLinkEntity resolveGuest(String token, String sessionToken) {
        ProjectShareLinkEntity link = requireUsable(token);
        if (!StringUtils.hasText(sessionToken)) {
            throw sessionExpired();
        }
        ProjectShareSessionEntity session = sessionMapper.selectOne(
                Wrappers.<ProjectShareSessionEntity>lambdaQuery()
                        .eq(ProjectShareSessionEntity::getLinkId, link.getId())
                        .eq(ProjectShareSessionEntity::getTokenHash, hash(sessionToken))
                        .last("LIMIT 1"));
        if (session == null || !session.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw sessionExpired();
        }
        return link;
    }

    public GuestAccess access(ProjectShareLinkEntity link) {
        ProjectEntity project = projectService.get(link.getProjectId());
        return new GuestAccess(project.getTitle(), project.getStatus(), link.getName(),
                Boolean.TRUE.equals(link.getAllowDownload()),
                Boolean.TRUE.equals(link.getAllowAdoption()),
                link.getExpiresAt());
    }

    /**
     * 访客能看到的图片：项目相册里状态为 AVAILABLE 的那些。
     *
     * <p>刻意不带上 {@code photographerStudentId}、上传者和校区——学号是个人信息，
     * 站外的人没有理由拿到。</p>
     */
    public PageResponse<SharePhotoView> photos(ProjectShareLinkEntity link, int page, int pageSize,
                                               String keyword) {
        String likeKeyword = LikeFilter.escape(keyword);
        Page<PhotoEntity> result = photoMapper.selectPage(Page.of(page, pageSize),
                Wrappers.<PhotoEntity>lambdaQuery()
                        .inSql(PhotoEntity::getId,
                                "SELECT photo_id FROM photo_project WHERE project_id = " + link.getProjectId())
                        .eq(PhotoEntity::getStatus, PhotoStatus.AVAILABLE)
                        .and(StringUtils.hasText(keyword), q -> q
                                .apply(LikeFilter.contains("title"), likeKeyword)
                                .or().apply(LikeFilter.contains("description"), likeKeyword)
                                .or().apply(LikeFilter.contains("tags_json"), likeKeyword))
                        .orderByDesc(PhotoEntity::getCreatedAt));
        Set<Long> adopted = adoptedPhotoIds(link.getProjectId(),
                result.getRecords().stream().map(PhotoEntity::getId).toList());
        return new PageResponse<>(result.getRecords().stream()
                .map(photo -> new SharePhotoView(photo.getId(), photo.getTitle(), photo.getDescription(),
                        photo.getPhotographerName(), photo.getTakenAt(), photo.getWidth(), photo.getHeight(),
                        photo.getSize(), photo.getStoredFileName(), photoService.previewUrl(photo),
                        adopted.contains(photo.getId())))
                .toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public PhotoService.DownloadUrl download(ProjectShareLinkEntity link, Long photoId) {
        requireDownloadAllowed(link);
        PhotoEntity photo = requireSharedPhoto(link, photoId);
        ObjectStorageService.SignedUrl signed = storage.presignGet(
                photo.getObjectKey(), photo.getStoredFileName(), storageProperties.downloadUrlTtl());
        return new PhotoService.DownloadUrl(signed.url().toString(), signed.expiresAt(),
                photo.getStoredFileName());
    }

    @Transactional
    public ExportJobEntity batchDownload(ProjectShareLinkEntity link, List<Long> photoIds) {
        requireDownloadAllowed(link);
        if (photoIds == null || photoIds.isEmpty() || photoIds.size() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "批量下载需选择 1 至 200 张图片");
        }
        List<Long> distinct = photoIds.stream().distinct().toList();
        // 逐张确认仍在这条链接的可见集合里：前端的选择可能来自一屏过期的列表，
        // 而"打包"绝不能成为绕过可见范围把别的图片弄出去的路径。
        for (Long photoId : distinct) {
            requireSharedPhoto(link, photoId);
        }
        return exportService.createShareZip(distinct, link.getCreatedBy(), link.getId());
    }

    public ExportService.JobView batchDownloadStatus(ProjectShareLinkEntity link, String jobId) {
        requireDownloadAllowed(link);
        return exportService.viewForShare(jobId, link.getId());
    }

    @Transactional
    public SharePhotoAdoption adopt(ProjectShareLinkEntity link, Long photoId) {
        requireAdoptionAllowed(link);
        PhotoEntity photo = requireSharedPhoto(link, photoId);
        adoptionService.adoptOnBehalf(link.getProjectId(), List.of(photo.getId()),
                shareRemark(link), link.getCreatedBy());
        return new SharePhotoAdoption(photo.getId(), true);
    }

    @Transactional
    public SharePhotoAdoption cancelAdoption(ProjectShareLinkEntity link, Long photoId) {
        requireAdoptionAllowed(link);
        PhotoEntity photo = requireSharedPhoto(link, photoId);
        AdoptionEntity adoption = adoptionMapper.selectOne(Wrappers.<AdoptionEntity>lambdaQuery()
                .eq(AdoptionEntity::getProjectId, link.getProjectId())
                .eq(AdoptionEntity::getPhotoId, photo.getId())
                .last("LIMIT 1"));
        if (adoption == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "这张图片还没有被标记被引");
        }
        adoptionService.cancel(link.getProjectId(), adoption.getId());
        return new SharePhotoAdoption(photo.getId(), false);
    }

    private String shareRemark(ProjectShareLinkEntity link) {
        // 记进 adoption.remark，让站内看到这条被引是谁通过哪条链接标的。
        return StringUtils.hasText(link.getName())
                ? "分享链接「" + link.getName() + "」标记" : "分享链接标记";
    }

    private void requireDownloadAllowed(ProjectShareLinkEntity link) {
        if (!Boolean.TRUE.equals(link.getAllowDownload())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "这条分享链接没有开放下载");
        }
    }

    private void requireAdoptionAllowed(ProjectShareLinkEntity link) {
        if (!Boolean.TRUE.equals(link.getAllowAdoption())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "这条分享链接没有开放标记被引");
        }
    }

    /** 图片必须仍在这条链接所属项目的相册里且可用，否则一律当成不存在。 */
    private PhotoEntity requireSharedPhoto(ProjectShareLinkEntity link, Long photoId) {
        PhotoEntity photo = photoId == null ? null : photoMapper.selectById(photoId);
        if (photo == null || photo.getStatus() != PhotoStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或已不可用");
        }
        long membership = jdbc.sql(
                        "SELECT COUNT(*) FROM photo_project WHERE photo_id=:photoId AND project_id=:projectId")
                .param("photoId", photo.getId()).param("projectId", link.getProjectId())
                .query(Long.class).single();
        if (membership == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片不属于这条分享链接的项目");
        }
        return photo;
    }

    private Set<Long> adoptedPhotoIds(Long projectId, List<Long> photoIds) {
        if (photoIds.isEmpty()) return Set.of();
        return Set.copyOf(adoptionMapper.selectList(Wrappers.<AdoptionEntity>lambdaQuery()
                        .eq(AdoptionEntity::getProjectId, projectId)
                        .in(AdoptionEntity::getPhotoId, photoIds))
                .stream().map(AdoptionEntity::getPhotoId).toList());
    }

    /**
     * 已撤销、已过期和不存在的链接一律回 404：站外的人不该从错误码里读出
     * "这个 token 曾经存在过"。
     */
    private ProjectShareLinkEntity requireUsable(String token) {
        ProjectShareLinkEntity link = StringUtils.hasText(token)
                ? mapper.selectOne(Wrappers.<ProjectShareLinkEntity>lambdaQuery()
                        .eq(ProjectShareLinkEntity::getToken, token).last("LIMIT 1"))
                : null;
        if (link == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "分享链接不存在或已被撤销");
        }
        if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "分享链接不存在或已被撤销");
        }
        return link;
    }

    /**
     * 会话失效用 403 而不是语义更贴切的 401，与文档中心同一个理由：401 会撞上
     * 前端 {@code api.ts} 的令牌刷新拦截器，给匿名访客白白发一次注定失败的
     * {@code /auth/refresh}，还会广播一次"会话过期"。
     */
    private BusinessException sessionExpired() {
        return new BusinessException(ErrorCode.FORBIDDEN, "分享访问已过期，请重新输入密码");
    }

    private ShareLinkView view(ProjectShareLinkEntity link) {
        boolean expired = link.getExpiresAt() != null
                && !link.getExpiresAt().isAfter(LocalDateTime.now(clock));
        return new ShareLinkView(link.getId(), link.getToken(), link.getProjectId(), link.getName(),
                Boolean.TRUE.equals(link.getAllowDownload()), Boolean.TRUE.equals(link.getAllowAdoption()),
                link.getExpiresAt(), expired, link.getViewCount() == null ? 0 : link.getViewCount(),
                link.getLastViewedAt(), link.getCreatedBy(), link.getCreatedAt(), link.getVersion());
    }

    private String validatedPassword(String password) {
        String trimmed = password.trim();
        if (trimmed.length() < MIN_PASSWORD_LENGTH || trimmed.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "分享密码长度需在 " + MIN_PASSWORD_LENGTH + " 到 " + MAX_PASSWORD_LENGTH + " 位之间");
        }
        return trimmed;
    }

    private LocalDateTime validatedExpiry(LocalDateTime expiresAt) {
        if (expiresAt == null) return null;
        if (!expiresAt.isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "失效时间必须晚于当前时间");
        }
        return expiresAt;
    }

    private static String generatePassword() {
        char[] value = new char[GENERATED_PASSWORD_LENGTH];
        for (int index = 0; index < value.length; index++) {
            value[index] = PASSWORD_ALPHABET[SECURE_RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        }
        return new String(value);
    }

    private static String newSessionToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    public record CreateCommand(String name, String password, boolean allowDownload,
                                boolean allowAdoption, LocalDateTime expiresAt) {}

    public record UpdateCommand(String name, boolean allowDownload, boolean allowAdoption,
                                LocalDateTime expiresAt, int version) {}

    public record ShareLinkView(Long id, String token, Long projectId, String name,
                                boolean allowDownload, boolean allowAdoption,
                                LocalDateTime expiresAt, boolean expired, long viewCount,
                                LocalDateTime lastViewedAt, Long createdBy,
                                LocalDateTime createdAt, Integer version) {}

    /** 明文密码只在创建和重置时出现一次，之后系统里只剩哈希。 */
    public record CreatedShareLink(ShareLinkView link, String password) {}

    public record ResetPassword(ShareLinkView link, String password) {}

    public record LinkGreeting(boolean requiresPassword) {}

    public record GuestSession(String sessionToken, LocalDateTime expiresAt, GuestAccess access) {}

    public record GuestAccess(String projectTitle, ProjectStatus projectStatus, String linkName,
                              boolean allowDownload, boolean allowAdoption, LocalDateTime expiresAt) {}

    public record SharePhotoView(Long id, String title, String description, String photographerName,
                                 LocalDateTime takenAt, Integer width, Integer height, Long size,
                                 String storedFileName, String thumbnailUrl, boolean adopted) {}

    public record SharePhotoAdoption(Long photoId, boolean adopted) {}
}

package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.PhotoTags;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.share.ProjectShareService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 活动选题的选片（issue #94）。
 *
 * <p>选片就是打标签，所以这里刻意只做两件 {@link PhotoService#batchTags} 做不到的事：</p>
 * <ol>
 *   <li><b>换一套授权</b>。选片人通常既没有 {@code PHOTO_UPLOAD} 也没有
 *       {@code REQUEST_PHOTO_MANAGE}——他不上传也不管需求，只判「这张能不能用」。
 *       授权凭据是 {@code project_selector} 里的那一行，逐张的上传者/校区限制不适用：
 *       活动选题的相册本来就是一整批别人拍的图。</li>
 *   <li><b>放行保留标签</b>。{@code deprecated} 与选题预设无关，见
 *       {@link PhotoTags#isReserved}。</li>
 * </ol>
 *
 * <p>标签的合并顺序（先删后加）、上限和规范化仍与 {@code batchTags} 完全一致，
 * 两边算出来的结果必须相同，否则同一张图在选题详情页和选片页会显示不同的标签。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectSelectionService {
    private final ProjectService projects;
    private final PhotoService photos;
    private final PhotoMapper photoMapper;
    private final JdbcClient jdbc;

    /** 确认框里展示多少张待删图片。 */
    static final int CLEANUP_SAMPLE_SIZE = 24;

    /**
     * 选片页的图片列表。
     *
     * <p>刻意不复用 {@code GET /photos?projectId=}：那条接口要求 {@code PHOTO_VIEW}，
     * 并按上传者/校区逐张收窄可见范围，而选片人通常两样都没有——他要看的恰恰是
     * 别人拍的那一整批。凭据同样只有一条：{@code project_selector} 里的那一行。</p>
     *
     * <p>只返回 {@code AVAILABLE} / {@code ARCHIVED}：还在上传或处理中的图片没有
     * 成品图，选片人点开只会看到一片空白。按 id 升序，与拍摄顺序一致，
     * 这样左边那列缩略图翻起来和相机里一样。</p>
     */
    public PageResponse<SelectionPhoto> photos(Long projectId, int page, int pageSize, AuthenticatedUser user) {
        projects.requireSelectionAccess(projectId, user);
        Page<PhotoEntity> result = photoMapper.selectPage(Page.of(page, pageSize),
                Wrappers.<PhotoEntity>lambdaQuery()
                        .inSql(PhotoEntity::getId,
                                "SELECT photo_id FROM photo_project WHERE project_id = " + projectId)
                        .in(PhotoEntity::getStatus, PhotoStatus.AVAILABLE, PhotoStatus.ARCHIVED)
                        .orderByAsc(PhotoEntity::getId));
        Page<SelectionPhoto> view = Page.of(result.getCurrent(), result.getSize(), result.getTotal());
        view.setRecords(result.getRecords().stream().map(this::toSelectionPhoto).toList());
        return PageResponse.from(view);
    }

    /** 选片页展示大图用的短期签名地址（成品图，内联渲染）。 */
    public PhotoService.DownloadUrl imageUrl(Long projectId, Long photoId, AuthenticatedUser user) {
        projects.requireSelectionAccess(projectId, user);
        return photos.fullImageUrl(requireAlbumPhoto(projectId, photoId));
    }

    /** 编辑保存前的授权与状态校验；签名与替换本身由 {@code PhotoImageEditService} 负责。 */
    public PhotoEntity requireEditablePhoto(Long projectId, Long photoId, AuthenticatedUser user) {
        ProjectEntity project = projects.requireSelectionAccess(projectId, user);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有进行中的选题可以编辑图片");
        }
        return requireAlbumPhoto(projectId, photoId);
    }

    /**
     * 列表里就把「大图」地址一起签出来。
     *
     * <p>选片页的默认动作就是看原图，逐张再问一次后端等于把一次翻页变成 60 次请求；
     * 签名本身只是一次 HMAC，一起签出来几乎不花钱。签名有效期与预览一致，
     * 页面按 {@code useRefreshOnResume} 的老规矩整页重取换新地址。</p>
     */
    private SelectionPhoto toSelectionPhoto(PhotoEntity photo) {
        String imageUrl = photo.getStatus() == PhotoStatus.AVAILABLE
                || photo.getStatus() == PhotoStatus.ARCHIVED
                ? photos.fullImageUrl(photo).downloadUrl() : null;
        return new SelectionPhoto(photo.getId(), photo.getTitle(), photo.getPhotographerName(),
                photo.getTakenAt(), PhotoTags.parse(photo.getTagsJson()), photo.getWidth(), photo.getHeight(),
                photo.getSize(), photo.getContentType(), photos.previewUrl(photo), imageUrl,
                photo.getStatus(), photo.getVersion());
    }

    /**
     * 给选题相册里的图片批量增删标签。
     *
     * @return 逐张的新标签与新版本号，前端用它就地更新而不必整页重取
     */
    @Transactional
    public List<PhotoService.TaggedPhoto> tag(Long projectId, List<Long> photoIds, List<String> addTags,
                                              List<String> removeTags, AuthenticatedUser user) {
        ProjectEntity project = projects.requireSelectionAccess(projectId, user);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有进行中的选题可以选片");
        }
        List<Long> ids = photoIds == null ? List.of()
                : photoIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1 至 200 张图片");
        }
        List<String> add = PhotoTags.normalize(addTags);
        List<String> remove = PhotoTags.normalize(removeTags);
        if (add.isEmpty() && remove.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请至少选择一个要添加或删除的标签");
        }
        projects.requireAllowedPhotoTags(projectId, add);

        List<PhotoService.TaggedPhoto> result = new ArrayList<>();
        for (Long photoId : ids) {
            PhotoEntity photo = requireAlbumPhoto(projectId, photoId);
            List<String> existing = PhotoTags.parse(photo.getTagsJson());
            Set<String> merged = new LinkedHashSet<>(existing);
            remove.forEach(merged::remove);
            merged.addAll(add);
            if (merged.size() > PhotoTags.MAX_TAGS) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "图片「"
                        + Objects.requireNonNullElse(photo.getTitle(), "#" + photo.getId())
                        + "」的标签将超过 " + PhotoTags.MAX_TAGS + " 个");
            }
            List<String> next = List.copyOf(merged);
            if (next.equals(existing)) {
                result.add(new PhotoService.TaggedPhoto(photo.getId(), existing, photo.getVersion()));
                continue;
            }
            // 与 PhotoService.batchTags 同一个理由：同一事务里绕过 MyBatis 直接写库，
            // 之后的 selectById 会读到改之前的标签。只写 tags_json 一列，版本条件手写。
            int updated = photoMapper.update(null, Wrappers.<PhotoEntity>lambdaUpdate()
                    .set(PhotoEntity::getTagsJson, PhotoTags.toJson(next))
                    .set(PhotoEntity::getUpdatedAt, LocalDateTime.now())
                    .setSql("version = version + 1")
                    .eq(PhotoEntity::getId, photo.getId())
                    .eq(PhotoEntity::getVersion, photo.getVersion()));
            if (updated != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片已被其他操作修改，请刷新后重试");
            }
            result.add(new PhotoService.TaggedPhoto(photo.getId(), next, photo.getVersion() + 1));
        }
        return result;
    }

    /**
     * 清理前的预演：按负责人选定的筛选条件，选题完成后要删掉哪些图片、哪些会被跳过。
     *
     * <p>删除不可撤销，所以界面上必须先把数字和一部分图片摆出来，再让人点第二次。
     * 返回的 {@code planToken} 是这批待删图片的指纹，执行清理时带回来，
     * 期间有人改了标签、采用了图片，指纹就对不上，清理会被拒绝，而不是删掉预览里没有的图。</p>
     */
    public CleanupPlan plan(Long projectId, CleanupFilter requested, AuthenticatedUser user) {
        ProjectEntity project = projects.requireManageableEventProject(projectId, user);
        CleanupCandidates candidates = candidates(projectId, CleanupFilter.orDefault(requested));
        return new CleanupPlan(project.getStatus() == ProjectStatus.COMPLETED,
                candidates.deletable().size(), candidates.adoptedSkipped(),
                samples(candidates.deletable()), planToken(candidates.deletable()));
    }

    /**
     * 把符合筛选条件的图片从图库和对象存储里删掉。
     *
     * <p>四条边界，少一条都会出事：</p>
     * <ul>
     *   <li><b>只在选题已完成之后</b>。选片还没结束时标签只是「当前判断」，
     *       删早了没人能改回来。</li>
     *   <li><b>被引（{@code adoption}）的图片一律跳过</b>，不管筛选条件怎么选、
     *       被哪个选题采用，与 {@code PhotoService.validateDelete} 的「已被采用的图片只能归档」
     *       同一条规则。筛选条件选了「已被引」，结果就是一张也不删。</li>
     *   <li><b>标签逐张用 {@link PhotoTags#parse} 判定</b>，不信任 LIKE。</li>
     *   <li><b>带了 {@code planToken} 就必须和当下的结果一致</b>：负责人确认的是预览里那一批，
     *       不是点确认那一刻碰巧符合条件的那一批。</li>
     * </ul>
     *
     * <p>没有任何筛选条件时按「不可用」标签清理，与引入筛选之前的行为一致（MCP 工具仍然这样调）。</p>
     */
    @Transactional
    public CleanupResult cleanup(Long projectId, CleanupFilter requested, String expectedPlanToken,
                                 AuthenticatedUser user) {
        ProjectEntity project = projects.requireManageableEventProject(projectId, user);
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "选题完成之后才能清理图片");
        }
        CleanupFilter filter = CleanupFilter.orDefault(requested);
        CleanupCandidates candidates = candidates(projectId, filter);
        if (expectedPlanToken != null && !expectedPlanToken.equals(planToken(candidates.deletable()))) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "确认之后有图片的标签或采用状态发生了变化，请重新预览后再删除");
        }
        int deleted = 0;
        for (Long photoId : candidates.deletable()) {
            PhotoEntity photo = photoMapper.selectById(photoId);
            if (photo == null) continue;
            photos.deleteForSelectionCleanup(photo);
            deleted++;
        }
        log.info("活动选题清理完成: projectId={}, filter={}, deleted={}, skippedAdopted={}, operator={}",
                projectId, filter, deleted, candidates.adoptedSkipped(), user.id());
        return new CleanupResult(deleted, candidates.adoptedSkipped());
    }

    /**
     * 选题相册里符合条件的图片，按 id 升序分成「会删」和「因被引跳过」两堆。
     *
     * <p>筛选规则与选题详情页那一排完全一致（{@code src/photoTags.ts} 的 {@code filterPhotos}）：
     * 标签同时包含、拍摄者任意其一、拍摄日期两端都含整天、「被引」指在本选题里被采用。
     * 只看 {@code AVAILABLE} / {@code ARCHIVED}：还在上传或处理中的图片正被流水线写着，
     * 这时删掉对象只会让处理任务失败，留给各自的清理任务去收尾。</p>
     */
    private CleanupCandidates candidates(Long projectId, CleanupFilter filter) {
        Set<Long> adoptedHere = new HashSet<>(jdbc.sql(
                        "SELECT DISTINCT photo_id FROM adoption WHERE project_id = :projectId AND deleted = 0")
                .param("projectId", projectId)
                .query(Long.class).list());
        // 跳过的依据是「在任何选题里被采用过」，比筛选里的「本选题被引」更宽。
        Set<Long> adoptedAnywhere = new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT a.photo_id FROM adoption a
                        JOIN photo_project pp ON pp.photo_id = a.photo_id
                        WHERE pp.project_id = :projectId AND a.deleted = 0
                        """)
                .param("projectId", projectId)
                .query(Long.class).list());
        List<CleanupFacets> album = jdbc.sql("""
                        SELECT p.id, p.tags_json, p.photographer_name, p.taken_at
                        FROM photo p JOIN photo_project pp ON pp.photo_id = p.id
                        WHERE pp.project_id = :projectId AND p.deleted = 0
                          AND p.status IN ('AVAILABLE', 'ARCHIVED')
                        ORDER BY p.id
                        """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new CleanupFacets(rs.getLong("id"),
                        PhotoTags.parse(rs.getString("tags_json")), rs.getString("photographer_name"),
                        rs.getObject("taken_at", LocalDateTime.class)))
                .list();
        List<Long> deletable = new ArrayList<>();
        int skipped = 0;
        for (CleanupFacets photo : album) {
            if (!filter.matches(photo, adoptedHere.contains(photo.id()))) continue;
            if (adoptedAnywhere.contains(photo.id())) {
                skipped++;
            } else {
                deletable.add(photo.id());
            }
        }
        return new CleanupCandidates(List.copyOf(deletable), skipped);
    }

    /** 均匀地从待删图片里挑几张给人看，而不是只给最前面那几张——那几张往往是同一组连拍。 */
    private List<CleanupSample> samples(List<Long> deletable) {
        int count = Math.min(CLEANUP_SAMPLE_SIZE, deletable.size());
        List<CleanupSample> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            PhotoEntity photo = photoMapper.selectById(deletable.get((int) ((long) index * deletable.size() / count)));
            if (photo == null) continue;
            result.add(new CleanupSample(photo.getId(), photo.getTitle(), photo.getPhotographerName(),
                    photo.getTakenAt(), PhotoTags.parse(photo.getTagsJson()), photos.previewUrl(photo)));
        }
        return result;
    }

    /** 待删图片 id 的指纹；空集合也有固定的指纹，这样「一张也不删」同样可以被确认。 */
    static String planToken(List<Long> deletable) {
        StringBuilder ids = new StringBuilder();
        for (Long id : deletable) ids.append(id).append(',');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ids.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private PhotoEntity requireAlbumPhoto(Long projectId, Long photoId) {
        PhotoEntity photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在");
        }
        long inAlbum = jdbc.sql(
                "SELECT COUNT(*) FROM photo_project WHERE photo_id=:photoId AND project_id=:projectId")
                .param("photoId", photoId)
                .param("projectId", projectId)
                .query(Long.class).single();
        if (inAlbum == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "所选图片不在该选题中，请刷新后重试");
        }
        if (photo.getStatus() != PhotoStatus.AVAILABLE && photo.getStatus() != PhotoStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片当前不可选片");
        }
        return photo;
    }

    /** 选片页需要的那几列。比 {@code PhotoView} 少：没有学号、上传者和校区。 */
    public record SelectionPhoto(Long id, String title, String photographerName, LocalDateTime takenAt,
                                 List<String> tags, Integer width, Integer height, Long size,
                                 String contentType, String thumbnailUrl, String imageUrl,
                                 PhotoStatus status, Integer version) {
    }

    /**
     * 清理的筛选条件，与选题详情页那一排筛选一一对应。{@code null} 字段表示不限。
     *
     * @param tags          同时包含这些标签；「不可用」存的是 {@code deprecated}
     * @param takenFrom     拍摄日期下界，含当天
     * @param takenTo       拍摄日期上界，含当天
     * @param photographers 拍摄者任意其一
     * @param adoption      本选题里是否被引
     */
    public record CleanupFilter(List<String> tags, LocalDate takenFrom, LocalDate takenTo,
                                List<String> photographers, ProjectShareService.AdoptionFilter adoption) {
        /** 引入筛选之前的唯一规则：只删标了「不可用」的。 */
        public static final CleanupFilter DEPRECATED_ONLY =
                new CleanupFilter(List.of(PhotoTags.DEPRECATED), null, null, List.of(), null);

        public CleanupFilter {
            tags = PhotoTags.normalize(tags);
            photographers = photographers == null ? List.of()
                    : photographers.stream().filter(StringUtils::hasText).map(String::strip).distinct().toList();
            if (takenFrom != null && takenTo != null && takenFrom.isAfter(takenTo)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄开始日期不能晚于结束日期");
            }
        }

        /** 一个条件都没有等于「整个相册」——那不是筛选，按旧规则只删「不可用」的。 */
        static CleanupFilter orDefault(CleanupFilter filter) {
            return filter == null || filter.isEmpty() ? DEPRECATED_ONLY : filter;
        }

        boolean isEmpty() {
            return tags.isEmpty() && photographers.isEmpty() && takenFrom == null && takenTo == null
                    && adoption == null;
        }

        boolean matches(CleanupFacets photo, boolean adoptedHere) {
            for (String tag : tags) {
                // 保留标签按 PhotoTags.isReserved 判定（不分大小写），与引入筛选之前的清理规则一致。
                boolean present = PhotoTags.isReserved(tag)
                        ? photo.tags().stream().anyMatch(PhotoTags::isReserved)
                        : photo.tags().contains(tag);
                if (!present) return false;
            }
            if (!photographers.isEmpty() && !photographers.contains(photo.photographerName())) return false;
            LocalDate day = photo.takenAt() == null ? null : photo.takenAt().toLocalDate();
            if (takenFrom != null && (day == null || day.isBefore(takenFrom))) return false;
            if (takenTo != null && (day == null || day.isAfter(takenTo))) return false;
            return adoption == null || adoptedHere == (adoption == ProjectShareService.AdoptionFilter.ADOPTED);
        }
    }

    private record CleanupFacets(Long id, List<String> tags, String photographerName, LocalDateTime takenAt) {
    }

    private record CleanupCandidates(List<Long> deletable, int adoptedSkipped) {
    }

    /** 确认框里给人看的一张待删图片。 */
    public record CleanupSample(Long id, String title, String photographerName, LocalDateTime takenAt,
                                List<String> tags, String thumbnailUrl) {
    }

    /**
     * @param ready     选题是否已经完成——没完成时只能预览，不能执行
     * @param samples   从待删图片里均匀挑出来的若干张（至多 {@code CLEANUP_SAMPLE_SIZE}）
     * @param planToken 待删图片的指纹，执行清理时原样带回
     */
    public record CleanupPlan(boolean ready, long deletableCount, long adoptedSkippedCount,
                              List<CleanupSample> samples, String planToken) {
    }

    public record CleanupResult(int deletedCount, int skippedAdoptedCount) {
    }
}

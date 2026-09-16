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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
     * 清理前的预演：选题完成后要删掉哪些图片、哪些会被跳过。
     *
     * <p>删除不可撤销，所以界面上必须先把数字摆出来，再让人点第二次。</p>
     */
    public CleanupPlan plan(Long projectId, AuthenticatedUser user) {
        ProjectEntity project = projects.requireManageableEventProject(projectId, user);
        List<PhotoEntity> candidates = deprecatedPhotos(projectId);
        long adopted = candidates.stream().filter(photo -> adoptionCount(photo.getId()) > 0).count();
        return new CleanupPlan(project.getStatus() == ProjectStatus.COMPLETED,
                candidates.size() - adopted, adopted);
    }

    /**
     * 把打了 {@code deprecated} 的图片从图库和对象存储里删掉。
     *
     * <p>三条边界，少一条都会出事：</p>
     * <ul>
     *   <li><b>只在选题已完成之后</b>。选片还没结束时 deprecated 只是「当前判断」，
     *       删早了没人能改回来。</li>
     *   <li><b>被引（{@code adoption}）的图片一律跳过</b>，与
     *       {@code PhotoService.validateDelete} 的「已被采用的图片只能归档」同一条规则。
     *       活动选题里出现被引的 deprecated 图片本身就说明有人改了主意，
     *       这时候该让人自己去处理，而不是替他删掉。</li>
     *   <li><b>逐张用 {@link PhotoTags#parse} 重新判定</b>，不信任列表查询里的 LIKE。</li>
     * </ul>
     */
    @Transactional
    public CleanupResult cleanup(Long projectId, AuthenticatedUser user) {
        ProjectEntity project = projects.requireManageableEventProject(projectId, user);
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "选题完成之后才能清理未选中的图片");
        }
        int deleted = 0;
        int skipped = 0;
        for (PhotoEntity photo : deprecatedPhotos(projectId)) {
            if (adoptionCount(photo.getId()) > 0) {
                skipped++;
                continue;
            }
            photos.deleteForSelectionCleanup(photo);
            deleted++;
        }
        log.info("活动选题清理完成: projectId={}, deleted={}, skippedAdopted={}, operator={}",
                projectId, deleted, skipped, user.id());
        return new CleanupResult(deleted, skipped);
    }

    private List<PhotoEntity> deprecatedPhotos(Long projectId) {
        List<Long> ids = jdbc.sql("""
                SELECT p.id FROM photo p JOIN photo_project pp ON pp.photo_id = p.id
                WHERE pp.project_id = :projectId AND p.deleted = 0
                  AND p.tags_json LIKE :needle
                ORDER BY p.id
                """)
                .param("projectId", projectId)
                .param("needle", "%" + PhotoTags.DEPRECATED + "%")
                .query((rs, rowNum) -> rs.getLong("id"))
                .list();
        return ids.stream()
                .map(photoMapper::selectById)
                .filter(Objects::nonNull)
                .filter(photo -> PhotoTags.parse(photo.getTagsJson()).stream().anyMatch(PhotoTags::isReserved))
                .toList();
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

    private long adoptionCount(Long photoId) {
        return jdbc.sql("SELECT COUNT(*) FROM adoption WHERE photo_id=:photoId AND deleted=0")
                .param("photoId", photoId)
                .query(Long.class).single();
    }

    /** 选片页需要的那几列。比 {@code PhotoView} 少：没有学号、上传者和校区。 */
    public record SelectionPhoto(Long id, String title, String photographerName, LocalDateTime takenAt,
                                 List<String> tags, Integer width, Integer height, Long size,
                                 String contentType, String thumbnailUrl, String imageUrl,
                                 PhotoStatus status, Integer version) {
    }

    /** @param ready 选题是否已经完成——没完成时前端只展示数字、不给清理按钮 */
    public record CleanupPlan(boolean ready, long deletableCount, long adoptedSkippedCount) {
    }

    public record CleanupResult(int deletedCount, int skippedAdoptedCount) {
    }
}

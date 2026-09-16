package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.photo.PhotoTags;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 活动选题的选片流程（issue #94）：指派选片人、打标签、清理未选中的图片。
 *
 * <p>这一组测试盯住的都是「少一条就出事」的边界：选片人凭指派看选题（而不是靠图库权限）、
 * {@code deprecated} 不受预设标签限制、清理只在选题完成后发生且永不碰被引的图片。</p>
 */
@SpringBootTest
@Transactional
class EventProjectSelectionTests {
    private static final long CAMPUS_ID = 9500L;
    private static final long OWNER_ID = 9600L;
    private static final long SELECTOR_ID = 9601L;
    private static final long OUTSIDER_ID = 9602L;
    private static final long UPLOADER_ID = 9603L;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectSelectionService selectionService;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser owner;
    private AuthenticatedUser selector;
    private AuthenticatedUser outsider;
    private AuthenticatedUser uploader;
    private ProjectEntity event;
    private ProjectEntity creation;
    private int seed;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO campus (id, code, name, enabled, version, deleted)
                VALUES (9500, 'EVT', '活动校区', true, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (9600, 'evt-owner', 'hash', '活动负责人', 'MINISTER', null, true, false),
                    (9601, 'evt-selector', 'hash', '选片同学', 'CAMPUS_MANAGER', 9500, true, false),
                    (9602, 'evt-outsider', 'hash', '路人甲', 'CAMPUS_MANAGER', 9500, true, false),
                    (9603, 'evt-uploader', 'hash', '只会传图的同学', 'CAMPUS_MANAGER', 9500, true, false)
                """).update();
        owner = new AuthenticatedUser(OWNER_ID, "evt-owner", "活动负责人", UserRole.MINISTER, null, false);
        // 选片人刻意只有「看接到需求的选题」这一条权限：既没有 PROJECT_VIEW_ALL，
        // 也没有任何图库权限。能进选片页必须完全靠指派。
        selector = restrictedUser(SELECTOR_ID, "evt-selector", "选片同学");
        outsider = restrictedUser(OUTSIDER_ID, "evt-outsider", "路人甲");
        // 一条 PROJECT_* 都没有的权限组：进得了系统，但进不了选题模块。
        uploader = new AuthenticatedUser(UPLOADER_ID, "evt-uploader", "只会传图的同学",
                UserRole.CAMPUS_MANAGER, CAMPUS_ID, false, -1L, "UPLOADER", "上传组",
                DataScope.GLOBAL, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of(CAMPUS_ID));

        event = projectService.create("校庆晚会", "说明", ProjectStatus.ACTIVE,
                List.of("开幕", "合影", "颁奖"), ProjectType.EVENT, owner);
        creation = projectService.create("日常创作", "说明", ProjectStatus.ACTIVE,
                List.of(), ProjectType.CREATION, owner);
    }

    private AuthenticatedUser restrictedUser(long id, String username, String displayName) {
        return new AuthenticatedUser(id, username, displayName, UserRole.CAMPUS_MANAGER, CAMPUS_ID,
                false, -1L, "SELECTOR", "选片人组", DataScope.GLOBAL,
                Set.of(PermissionCode.PROJECT_VIEW), Set.of(CAMPUS_ID));
    }

    @Test
    void existingAndCreationProjectsAreNeverEventProjects() {
        assertThat(projectService.getDetail(creation.getId(), owner).type())
                .isEqualTo(ProjectType.CREATION);

        // 迁移之前建的行不带 type 这一列。V44 的 DEFAULT 'CREATION' 就是为了让它们
        // 落成创作选题——没有默认值的话它们会是 NULL，而「NULL 算哪一类」这个问题
        // 每个读到它的地方都得重答一遍。
        jdbc.sql("""
                INSERT INTO project (title, description, status, created_by, tags_json, version, deleted)
                VALUES ('迁移之前的选题', '说明', 'ACTIVE', 9600, '[]', 1, false)
                """).update();
        Long legacyId = jdbc.sql("SELECT id FROM project WHERE title = '迁移之前的选题'")
                .query(Long.class).single();
        var detail = projectService.getDetail(legacyId, owner);
        assertThat(detail.type()).isEqualTo(ProjectType.CREATION);
        assertThat(detail.canSelect()).isFalse();
        assertThat(detail.selectors()).isEmpty();
    }

    @Test
    void creationProjectsHaveNoSelectionWorkflow() {
        assertThatThrownBy(() -> projectService.requireSelectionAccess(creation.getId(), owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有活动选题");
        assertThatThrownBy(() -> projectService.replaceSelectors(creation.getId(), List.of(SELECTOR_ID), owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有活动选题");
    }

    @Test
    void beingAssignedIsItselfTheCredentialToSeeTheProject() {
        // 指派之前：没接过需求、又没有 PROJECT_VIEW_ALL，看不到这个选题。
        assertThatThrownBy(() -> projectService.getDetail(event.getId(), selector))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
        assertThat(projectService.list(1, 20, null, null, selector).items()).isEmpty();

        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);

        var detail = projectService.getDetail(event.getId(), selector);
        assertThat(detail.canSelect()).isTrue();
        // 但他只是选片人，不是负责人：改名单和清理图片仍然不归他。
        assertThat(detail.canManageSelection()).isFalse();
        assertThat(projectService.list(1, 20, null, null, selector).items())
                .extracting(ProjectEntity::getId).contains(event.getId());

        // 没被指派的人一点都没多出来。
        assertThatThrownBy(() -> projectService.requireSelectionAccess(event.getId(), outsider))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assignmentDoesNotSubstituteForTheProjectViewPermission() {
        // 指派放行的是「看不看得到这一个选题」，不是「进不进得了选题模块」。
        // 后者仍由权限组的 PROJECT_VIEW / PROJECT_VIEW_ALL 决定，且是三道门里的第一道
        // （控制器的 @PreAuthorize、requireViewPermission、前端路由 canViewProjects）。
        // 三个内置权限组都带 PROJECT_VIEW，所以只有自建的「无任何选题权限」权限组会撞上。
        projectService.replaceSelectors(event.getId(), List.of(UPLOADER_ID), owner);
        assertThat(projectService.isSelector(event.getId(), UPLOADER_ID)).isTrue();

        assertThatThrownBy(() -> projectService.requireSelectionAccess(event.getId(), uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权执行该选题操作");
        assertThatThrownBy(() -> projectService.getDetail(event.getId(), uploader))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权执行该选题操作");
    }

    @Test
    void replacingSelectorsOnlyNotifiesTheNewcomers() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        assertThat(notificationCount(SELECTOR_ID)).isEqualTo(1);

        // 把原来那位留着、再加一位：原来那位不该被重新通知一遍。
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID, OUTSIDER_ID), owner);
        assertThat(projectService.selectors(event.getId()))
                .extracting(ProjectService.Selector::userId)
                .containsExactlyInAnyOrder(SELECTOR_ID, OUTSIDER_ID);
        assertThat(notificationCount(SELECTOR_ID)).isEqualTo(1);
        assertThat(notificationCount(OUTSIDER_ID)).isEqualTo(1);

        // 传空数组就是取消全部指派，随即也就看不到这个选题了。
        projectService.replaceSelectors(event.getId(), List.of(), owner);
        assertThat(projectService.selectors(event.getId())).isEmpty();
        assertThatThrownBy(() -> projectService.requireSelectionAccess(event.getId(), selector))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void selectorsCannotEditTheSelectorRoster() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        assertThatThrownBy(() -> projectService.replaceSelectors(event.getId(),
                List.of(SELECTOR_ID, OUTSIDER_ID), selector))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> projectService.selectorCandidates(event.getId(), selector))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void selectorsTagWithoutAnyGalleryPermission() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        long photoId = insertAlbumPhoto(event.getId(), List.of());

        var tagged = selectionService.tag(event.getId(), List.of(photoId), List.of("合影"), List.of(), selector);
        assertThat(tagged).singleElement()
                .satisfies(item -> assertThat(item.tags()).containsExactly("合影"));
        assertThat(tags(photoId)).containsExactly("合影");

        // 删标签同样可以，而且先删后加的顺序与 PhotoService.batchTags 一致。
        var retagged = selectionService.tag(event.getId(), List.of(photoId),
                List.of("颁奖"), List.of("合影"), selector);
        assertThat(retagged).singleElement()
                .satisfies(item -> assertThat(item.tags()).containsExactly("颁奖"));
    }

    @Test
    void tagsOutsidePresetsAreRejectedButDeprecatedAlwaysPasses() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        long photoId = insertAlbumPhoto(event.getId(), List.of());

        assertThatThrownBy(() -> selectionService.tag(event.getId(), List.of(photoId),
                List.of("临时起意"), List.of(), selector))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许使用预设标签");

        // deprecated 表达的是「这张图用不上」，与选题想收哪几类图无关，因此不受预设限制。
        var tagged = selectionService.tag(event.getId(), List.of(photoId),
                List.of(PhotoTags.DEPRECATED), List.of(), selector);
        assertThat(tagged).singleElement()
                .satisfies(item -> assertThat(item.tags()).containsExactly(PhotoTags.DEPRECATED));
    }

    @Test
    void theReservedTagNeverBecomesAProjectPreset() {
        ProjectEntity project = projectService.create("带保留标签的选题", "说明", ProjectStatus.ACTIVE,
                List.of("开幕", PhotoTags.DEPRECATED, "合影"), ProjectType.EVENT, owner);
        assertThat(project.getTags()).containsExactly("开幕", "合影");

        ProjectEntity updated = projectService.update(project.getId(), "带保留标签的选题", "说明",
                List.of(PhotoTags.DEPRECATED), projectService.get(project.getId()).getVersion(), owner);
        assertThat(updated.getTags()).isEmpty();
    }

    @Test
    void photosOutsideTheAlbumCannotBeTagged() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        long elsewhere = insertAlbumPhoto(creation.getId(), List.of());
        assertThatThrownBy(() -> selectionService.tag(event.getId(), List.of(elsewhere),
                List.of("合影"), List.of(), selector))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在该选题中");
    }

    @Test
    void selectionIsLockedOnceTheProjectLeavesActive() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        long photoId = insertAlbumPhoto(event.getId(), List.of());
        complete();
        assertThatThrownBy(() -> selectionService.tag(event.getId(), List.of(photoId),
                List.of("合影"), List.of(), selector))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有进行中的选题");
    }

    @Test
    void cleanupIsRefusedBeforeTheProjectIsCompleted() {
        insertAlbumPhoto(event.getId(), List.of(PhotoTags.DEPRECATED));
        assertThat(selectionService.plan(event.getId(), owner).ready()).isFalse();
        assertThatThrownBy(() -> selectionService.cleanup(event.getId(), owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("选题完成之后");
    }

    @Test
    void cleanupRemovesDeprecatedPhotosAndObjectsButSkipsAdoptedOnes() {
        long dropped = insertAlbumPhoto(event.getId(), List.of(PhotoTags.DEPRECATED));
        long keptByTag = insertAlbumPhoto(event.getId(), List.of("合影"));
        long droppedButAdopted = insertAlbumPhoto(event.getId(), List.of("合影", PhotoTags.DEPRECATED));
        adopt(event.getId(), droppedButAdopted);
        String droppedKey = objectKey(dropped);
        assertThat(storage.find(droppedKey)).isPresent();

        complete();
        var plan = selectionService.plan(event.getId(), owner);
        assertThat(plan.ready()).isTrue();
        assertThat(plan.deletableCount()).isEqualTo(1);
        assertThat(plan.adoptedSkippedCount()).isEqualTo(1);

        var result = selectionService.cleanup(event.getId(), owner);
        assertThat(result.deletedCount()).isEqualTo(1);
        assertThat(result.skippedAdoptedCount()).isEqualTo(1);

        assertThat(deleted(dropped)).isTrue();
        assertThat(deleted(keptByTag)).isFalse();
        // 被引的那张一个字节都不能动——与「已被采用的图片只能归档」是同一条规则。
        assertThat(deleted(droppedButAdopted)).isFalse();
        assertThat(storage.find(droppedKey)).isEmpty();
    }

    @Test
    void aCustomTagMerelyContainingTheWordDeprecatedIsNotDeleted() {
        // LIKE '%deprecated%' 会把它捞出来，但逐张重新解析标签之后必须排除掉。
        long lookalike = insertAlbumPhoto(event.getId(), List.of("not-deprecated-at-all"));
        complete();
        assertThat(selectionService.plan(event.getId(), owner).deletableCount()).isZero();
        assertThat(selectionService.cleanup(event.getId(), owner).deletedCount()).isZero();
        assertThat(deleted(lookalike)).isFalse();
    }

    @Test
    void selectorsCannotTriggerCleanup() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        insertAlbumPhoto(event.getId(), List.of(PhotoTags.DEPRECATED));
        complete();
        assertThatThrownBy(() -> selectionService.cleanup(event.getId(), selector))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void theSelectionListOnlyReturnsUsablePhotosAndCarriesTheFullImageUrl() {
        projectService.replaceSelectors(event.getId(), List.of(SELECTOR_ID), owner);
        long available = insertAlbumPhoto(event.getId(), List.of("合影"));
        long processing = insertAlbumPhoto(event.getId(), List.of());
        jdbc.sql("UPDATE photo SET status='PROCESSING' WHERE id=:id").param("id", processing).update();

        var page = selectionService.photos(event.getId(), 1, 60, selector);
        assertThat(page.items()).extracting(ProjectSelectionService.SelectionPhoto::id)
                .containsExactly(available);
        // 选片默认就是看原图，地址跟列表一起签出来，翻一页不该变成 60 次请求。
        assertThat(page.items().getFirst().imageUrl()).isNotBlank();
        assertThat(page.items().getFirst().tags()).containsExactly("合影");
    }

    // ---------------------------------------------------------------- helpers

    private void complete() {
        ProjectEntity current = projectService.get(event.getId());
        projectService.changeStatus(event.getId(), ProjectStatus.COMPLETED, current.getVersion(), owner);
    }

    /** 直接建一张已经发布的图片并挂进相册；选片流程不关心它是怎么传上来的。 */
    private long insertAlbumPhoto(Long projectId, List<String> tags) {
        seed++;
        String objectKey = "photos/2026/event-" + projectId + "-" + seed + ".jpg";
        storage.put(objectKey, new ByteArrayInputStream("fake-jpeg".getBytes(StandardCharsets.UTF_8)),
                9, "image/jpeg");
        jdbc.sql("""
                INSERT INTO photo (project_id, title, photographer_student_id, photographer_name,
                                   uploaded_by, campus_id, taken_at, tags_json, width, height, size,
                                   content_type, object_key, stored_file_name, sha256, status, version, deleted)
                VALUES (:projectId, :title, '20269500', '活动拍摄者', 9600, 9500, CURRENT_TIMESTAMP,
                        :tags, 6000, 4000, 9, 'image/jpeg', :objectKey, 'photo.jpg', :sha, 'AVAILABLE', 1, false)
                """)
                .param("projectId", projectId)
                .param("title", "活动图片 " + seed)
                .param("tags", PhotoTags.toJson(tags))
                .param("objectKey", objectKey)
                .param("sha", String.format("%064d", seed))
                .update();
        long photoId = jdbc.sql("SELECT id FROM photo WHERE object_key = :objectKey")
                .param("objectKey", objectKey).query(Long.class).single();
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                .param("photoId", photoId).param("projectId", projectId).update();
        return photoId;
    }

    private void adopt(Long projectId, long photoId) {
        jdbc.sql("""
                INSERT INTO adoption (project_id, photo_id, photographer_student_id, photographer_name,
                                      adopted_by, adopted_at, deleted)
                VALUES (:projectId, :photoId, '20269500', '活动拍摄者', 9600, CURRENT_TIMESTAMP, false)
                """)
                .param("projectId", projectId).param("photoId", photoId).update();
    }

    private List<String> tags(long photoId) {
        return PhotoTags.parse(jdbc.sql("SELECT tags_json FROM photo WHERE id = :id")
                .param("id", photoId).query(String.class).single());
    }

    private String objectKey(long photoId) {
        return jdbc.sql("SELECT object_key FROM photo WHERE id = :id")
                .param("id", photoId).query(String.class).single();
    }

    private boolean deleted(long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single();
    }

    private long notificationCount(long userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id = :userId AND event_type = 'PROJECT_SELECTION_ASSIGNED'
                """)
                .param("userId", userId).query(Long.class).single();
    }
}

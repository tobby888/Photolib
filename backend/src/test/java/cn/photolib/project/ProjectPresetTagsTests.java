package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.PhotoService;
import cn.photolib.photo.PhotoTags;
import cn.photolib.photo.batch.BatchUploadService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.RequestService;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 选题预设标签：新建选题时定义的固定标签约束「往选题里上传」和「在选题里加标签」，
 * 不约束直接向图库上传；没有预设时上传者可以自定义。
 */
@SpringBootTest
@Transactional
class ProjectPresetTagsTests {
    private static final long CAMPUS_ID = 9100L;
    private static final long CONTACT_ID = 9300L;
    private static final long PRESET_REQUEST_ID = 9400L;
    private static final long FREE_REQUEST_ID = 9401L;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private PhotoService photoService;
    @Autowired
    private RequestService requestService;
    @Autowired
    private BatchUploadService batchUploadService;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser admin;
    private AuthenticatedUser manager;
    private ProjectEntity presetProject;
    private ProjectEntity freeProject;
    private int shaSeed;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO campus (id, code, name, enabled, version, deleted)
                VALUES (9100, 'TAGS', '标签校区', true, 1, false)
                """).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (9200, 'tag-admin', 'hash', '标签管理员', 'ADMIN', null, true, false),
                    (9201, 'tag-manager', 'hash', '标签负责人', 'CAMPUS_MANAGER', 9100, true, false)
                """).update();
        jdbc.sql("""
                INSERT INTO campus_member (id, campus_id, student_id, name, enabled, version, deleted)
                VALUES (9300, 9100, '20269300', '标签拍摄者', true, 1, false)
                """).update();
        admin = new AuthenticatedUser(9200L, "tag-admin", "标签管理员", UserRole.ADMIN, null, false);
        manager = new AuthenticatedUser(9201L, "tag-manager", "标签负责人", UserRole.CAMPUS_MANAGER,
                CAMPUS_ID, false);

        presetProject = projectService.create("毕业季", "说明", ProjectStatus.ACTIVE,
                List.of(" 合影 ", "毕业典礼", "合影", ""), admin);
        freeProject = projectService.create("日常", "说明", ProjectStatus.ACTIVE, admin);
        insertAcceptedRequest(PRESET_REQUEST_ID, presetProject.getId());
        insertAcceptedRequest(FREE_REQUEST_ID, freeProject.getId());
    }

    @Test
    void presetTagsAreNormalizedExposedAndSearchable() {
        assertThat(presetProject.getTags()).containsExactly("合影", "毕业典礼");
        assertThat(freeProject.getTags()).isEmpty();
        assertThat(projectService.getDetail(presetProject.getId(), admin).tags())
                .containsExactly("合影", "毕业典礼");

        var found = projectService.list(1, 20, "毕业典礼", null, admin).items();
        assertThat(found).extracting(ProjectEntity::getId).containsExactly(presetProject.getId());
    }

    @Test
    void updateKeepsPresetsWhenOmittedAndClearsThemWithAnEmptyList() {
        ProjectEntity kept = projectService.update(presetProject.getId(), "毕业季（改）", "说明",
                projectService.get(presetProject.getId()).getVersion(), admin);
        assertThat(kept.getTags()).containsExactly("合影", "毕业典礼");

        ProjectEntity changed = projectService.update(presetProject.getId(), "毕业季（改）", "说明",
                List.of("学位授予"), kept.getVersion(), admin);
        assertThat(changed.getTags()).containsExactly("学位授予");

        ProjectEntity cleared = projectService.update(presetProject.getId(), "毕业季（改）", "说明",
                List.of(), changed.getVersion(), admin);
        assertThat(cleared.getTags()).isEmpty();
    }

    @Test
    void requestUploadOnlyAcceptsTheProjectsPresetTags() {
        Long rejected = ticket(PRESET_REQUEST_ID, presetProject.getId(), manager);
        assertThatThrownBy(() -> complete(rejected, List.of("合影", "自定义"), manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许使用预设标签")
                .hasMessageContaining("自定义");
        assertThat(status(rejected)).isEqualTo("UPLOADING");

        Long accepted = ticket(PRESET_REQUEST_ID, presetProject.getId(), manager);
        var view = complete(accepted, List.of("合影"), manager);
        assertThat(view.tags()).containsExactly("合影");

        // 不打标签同样允许。
        Long untagged = ticket(PRESET_REQUEST_ID, presetProject.getId(), manager);
        assertThat(complete(untagged, List.of(), manager).tags()).isEmpty();
    }

    @Test
    void requestUploadWithoutPresetsAcceptsCustomTags() {
        Long photoId = ticket(FREE_REQUEST_ID, freeProject.getId(), manager);
        assertThat(complete(photoId, List.of("自定义", "随手拍"), manager).tags())
                .containsExactly("自定义", "随手拍");
    }

    @Test
    void directGalleryUploadIsNeverRestricted() {
        Long photoId = ticket(null, null, admin);
        assertThat(complete(photoId, List.of("任意标签"), admin).tags()).containsExactly("任意标签");
        assertThat(photoService.get(photoId, admin).tags()).containsExactly("任意标签");
    }

    @Test
    void zipMetadataForARequestBatchHonoursPresetTags() {
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, request_id, project_id, created_by, status, total_count, success_count, failure_count)
                VALUES ('batch-preset-tags', 'ZIP', :requestId, :projectId, 9201, 'WAITING_METADATA', 1, 0, 0)
                """).param("requestId", PRESET_REQUEST_ID).param("projectId", presetProject.getId()).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, content_type, size, status)
                VALUES ('batch-preset-tags', '合影.jpg', 'temporary/preset.jpg', 'image/jpeg', 100, 'WAITING_METADATA')
                """).update();

        assertThatThrownBy(() -> batchUploadService.setMetadataForAll("batch-preset-tags",
                new BatchUploadService.BatchMetadata("说明", CONTACT_ID, LocalDateTime.now(), List.of("自定义")),
                manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许使用预设标签");
        // 被拒时批次原样留在待整理状态，可以改完标签重新提交。
        assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id='batch-preset-tags'")
                .query(String.class).single()).isEqualTo("WAITING_METADATA");

        batchUploadService.setMetadataForAll("batch-preset-tags",
                new BatchUploadService.BatchMetadata("说明", CONTACT_ID, LocalDateTime.now(), List.of("毕业典礼")),
                manager);
        String tags = jdbc.sql("SELECT tags_json FROM photo WHERE request_id=:requestId")
                .param("requestId", PRESET_REQUEST_ID).query(String.class).single();
        assertThat(PhotoTags.parse(tags)).containsExactly("毕业典礼");
    }

    @Test
    void tagOptionsTellUploadersWhetherTheRequestIsRestricted() {
        var restricted = requestService.tagOptions(PRESET_REQUEST_ID, manager);
        assertThat(restricted.restricted()).isTrue();
        assertThat(restricted.tags()).containsExactly("合影", "毕业典礼");

        var free = requestService.tagOptions(FREE_REQUEST_ID, manager);
        assertThat(free.restricted()).isFalse();
        assertThat(free.tags()).isEmpty();
    }

    @Test
    void batchTagsInsideAProjectAddOnlyPresetsButRemoveAnything() {
        long first = insertPhoto(PRESET_REQUEST_ID, presetProject.getId(), 9201L, "[\"旧标签\"]");
        long second = insertPhoto(null, null, 9200L, "[]");
        link(second, presetProject.getId());

        var result = photoService.batchTags(new PhotoService.BatchTags(List.of(first, second),
                List.of("合影"), List.of("旧标签"), presetProject.getId()), admin);

        assertThat(result).extracting(PhotoService.TaggedPhoto::tags)
                .containsExactly(List.of("合影"), List.of("合影"));
        assertThat(photoService.get(first, admin).tags()).containsExactly("合影");
        assertThat(photoService.get(first, admin).version()).isEqualTo(2);

        assertThatThrownBy(() -> photoService.batchTags(new PhotoService.BatchTags(List.of(second),
                List.of("自定义"), List.of(), presetProject.getId()), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许使用预设标签");
    }

    @Test
    void batchTagsInsideAProjectRejectPhotosOutsideItAndRollBackEverything() {
        long inside = insertPhoto(null, null, 9200L, "[]");
        link(inside, presetProject.getId());
        long outside = insertPhoto(null, null, 9200L, "[]");

        assertThatThrownBy(() -> photoService.batchTags(new PhotoService.BatchTags(List.of(inside, outside),
                List.of("合影"), List.of(), presetProject.getId()), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在该选题中");
        assertThat(photoService.get(inside, admin).tags()).isEmpty();
    }

    @Test
    void galleryBatchTagsStillHonourTheSourceProjectsPresets() {
        long requestPhoto = insertPhoto(PRESET_REQUEST_ID, presetProject.getId(), 9201L, "[]");
        long galleryPhoto = insertPhoto(null, null, 9200L, "[]");

        assertThatThrownBy(() -> photoService.batchTags(new PhotoService.BatchTags(List.of(requestPhoto),
                List.of("自定义"), List.of(), null), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许使用预设标签");

        var result = photoService.batchTags(new PhotoService.BatchTags(List.of(galleryPhoto),
                List.of("自定义"), List.of(), null), admin);
        assertThat(result.getFirst().tags()).containsExactly("自定义");
    }

    @Test
    void batchTagsCheckMetadataPermissionForEveryPhoto() {
        long own = insertPhoto(null, null, 9201L, "[]");
        long someoneElses = insertPhoto(null, null, 9200L, "[]");

        assertThatThrownBy(() -> photoService.batchTags(new PhotoService.BatchTags(List.of(own, someoneElses),
                List.of("自定义"), List.of(), null), manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权编辑该图片");
        assertThat(photoService.get(own, manager).tags()).isEmpty();
    }

    @Test
    void batchTagsRejectEmptyChangesAndOverflowingTagLists() {
        long photo = insertPhoto(null, null, 9200L, "[]");
        assertThatThrownBy(() -> photoService.batchTags(new PhotoService.BatchTags(List.of(photo),
                List.of(" "), List.of(), null), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少选择一个");

        List<String> thirty = java.util.stream.IntStream.range(0, 30).mapToObj(i -> "标签" + i).toList();
        photoService.batchTags(new PhotoService.BatchTags(List.of(photo), thirty, List.of(), null), admin);
        assertThatThrownBy(() -> photoService.batchTags(new PhotoService.BatchTags(List.of(photo),
                List.of("第三十一个"), List.of(), null), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过 30 个");
    }

    @Test
    void singleEditOnlyRestrictsNewlyAddedTags() {
        long photo = insertPhoto(PRESET_REQUEST_ID, presetProject.getId(), 9201L, "[\"旧标签\"]");

        // 预设之外的旧标签可以原样保留，同时再加预设里的标签。
        var updated = photoService.update(photo, new PhotoService.Metadata("标题", "说明", CONTACT_ID,
                LocalDateTime.now().minusDays(1), List.of("旧标签", "合影"), 1), admin);
        assertThat(updated.tags()).containsExactly("旧标签", "合影");

        assertThatThrownBy(() -> photoService.update(photo, new PhotoService.Metadata("标题", "说明", CONTACT_ID,
                LocalDateTime.now().minusDays(1), List.of("自定义"), updated.version()), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许使用预设标签");
    }

    private void insertAcceptedRequest(long id, Long projectId) {
        jdbc.sql("""
                INSERT INTO photo_request (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '标签需求', 9100, :deadline, 'ACCEPTED', 9200)
                """).param("id", id).param("projectId", projectId)
                .param("deadline", LocalDateTime.now().plusDays(3)).update();
        jdbc.sql("""
                INSERT INTO request_participant (request_id, user_id, accepted_at)
                VALUES (:id, 9201, :acceptedAt)
                """).param("id", id).param("acceptedAt", LocalDateTime.now()).update();
    }

    private Long ticket(Long requestId, Long projectId, AuthenticatedUser user) {
        String sha = Integer.toHexString(++shaSeed);
        sha = "f".repeat(64 - sha.length()) + sha;
        var ticket = photoService.createTicket(new PhotoService.CreateTicket(requestId, projectId,
                "tag.jpg", "image/jpeg", 1024L, sha, CONTACT_ID, LocalDateTime.now().minusMinutes(1)), user);
        String originalKey = jdbc.sql("SELECT original_object_key FROM photo WHERE id=:id")
                .param("id", ticket.photoId()).query(String.class).single();
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        storage.put(originalKey, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        return ticket.photoId();
    }

    private PhotoService.PhotoView complete(Long photoId, List<String> tags, AuthenticatedUser user) {
        return photoService.complete(photoId, new PhotoService.CompleteUpload("标题", "说明", tags), user);
    }

    private String status(Long photoId) {
        return jdbc.sql("SELECT status FROM photo WHERE id=:id").param("id", photoId).query(String.class).single();
    }

    private long insertPhoto(Long requestId, Long projectId, long uploadedBy, String tagsJson) {
        String sha = Integer.toHexString(++shaSeed);
        sha = "c".repeat(64 - sha.length()) + sha;
        jdbc.sql("""
                INSERT INTO photo
                    (request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, tags_json, size, content_type, object_key, sha256, status)
                VALUES
                    (:requestId, :projectId, '标签图片', '20269300', '标签拍摄者', :uploadedBy, 9100,
                     :takenAt, :tags, 1000, 'image/jpeg', :objectKey, :sha, 'AVAILABLE')
                """)
                .param("requestId", requestId)
                .param("projectId", projectId)
                .param("uploadedBy", uploadedBy)
                .param("takenAt", LocalDateTime.now().minusDays(1))
                .param("tags", tagsJson)
                .param("objectKey", "photos/2026/tag-" + sha + ".jpg")
                .param("sha", sha)
                .update();
        long id = jdbc.sql("SELECT id FROM photo WHERE sha256=:sha").param("sha", sha).query(Long.class).single();
        if (projectId != null) link(id, projectId);
        return id;
    }

    private void link(long photoId, long projectId) {
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                .param("photoId", photoId).param("projectId", projectId).update();
    }
}

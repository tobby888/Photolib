package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理员直接改图片基本信息（单张 / 批量）：拍摄者不经通讯录、被引快照按需同步、
 * 只改给了的项、处理中的图不能改、非管理员一律拒绝。
 */
@SpringBootTest
@Transactional
class PhotoAdminMetadataTests {
    @Autowired
    PhotoService photos;
    @Autowired
    JdbcClient jdbc;

    private long base;
    private long campusId;
    private long otherCampusId;
    private long projectId;
    private long otherProjectId;
    private AuthenticatedUser admin;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & Long.MAX_VALUE >> 4;
        long adminId = base;
        campusId = base + 1;
        otherCampusId = base + 2;
        projectId = base + 3;
        otherProjectId = base + 4;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '原校区'), (:other, :otherCode, '新校区')")
                .param("id", campusId).param("code", "pam-" + base)
                .param("other", otherCampusId).param("otherCode", "pam2-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '信息管理员', 'ADMIN', TRUE, FALSE)
                """).param("id", adminId).param("username", "pam-" + base).update();
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by)
                VALUES (:id, '编辑选题', 'ACTIVE', :u), (:other, '另一个选题', 'ACTIVE', :u)
                """).param("id", projectId).param("other", otherProjectId).param("u", adminId).update();
        admin = new AuthenticatedUser(adminId, "pam", "信息管理员", UserRole.ADMIN, null, false);

        insertPhoto(base + 10, "AVAILABLE", adminId);
        insertPhoto(base + 11, "ARCHIVED", adminId);
        insertPhoto(base + 12, "PROCESSING", adminId);
        insertAdoption(base + 10, projectId, adminId, false);
        insertAdoption(base + 10, otherProjectId, adminId, true);
    }

    @Test
    void adminSetsAPhotographerWhoIsNotInTheDirectoryAndCorrectsTheAdoptionCredit() {
        PhotoService.AdminEdit edit = photos.adminUpdate(base + 10, new PhotoService.AdminMetadata(
                "  改过的标题 ", "", " 站外拍摄者 ", " 20990001 ", LocalDateTime.of(2024, 6, 1, 9, 30),
                null, List.of("新标签"), 1, true), admin);

        assertThat(edit.photo().title()).isEqualTo("改过的标题");
        assertThat(edit.photo().photographerName()).isEqualTo("站外拍摄者");
        assertThat(edit.photo().photographerStudentId()).isEqualTo("20990001");
        assertThat(edit.photo().description()).isNull();
        assertThat(edit.photo().campusId()).isNull();
        assertThat(edit.photo().takenAt()).isEqualTo(LocalDateTime.of(2024, 6, 1, 9, 30));
        assertThat(edit.photo().tags()).containsExactly("新标签");
        assertThat(edit.photo().version()).isEqualTo(2);
        assertThat(edit.previous()).containsEntry("photographerName", "原拍摄者")
                .containsEntry("photographerStudentId", "20240001")
                .containsEntry("campusId", campusId);
        // 只改未撤销的那条；撤销过的再被引时会重新取快照。
        assertThat(edit.adoptionsUpdated()).isEqualTo(1);
        assertThat(adoptionPhotographer(base + 10, projectId)).isEqualTo("站外拍摄者/20990001");
        assertThat(adoptionPhotographer(base + 10, otherProjectId)).isEqualTo("原拍摄者/20240001");
    }

    @Test
    void adoptionSnapshotStaysWhenTheAdminOptsOut() {
        PhotoService.AdminEdit edit = photos.adminUpdate(base + 10, metadata("另一个人", "20990002", 1, false), admin);

        assertThat(edit.adoptionsUpdated()).isZero();
        assertThat(edit.photo().photographerName()).isEqualTo("另一个人");
        assertThat(adoptionPhotographer(base + 10, projectId)).isEqualTo("原拍摄者/20240001");
    }

    @Test
    void staleVersionsProcessingPhotosAndNonAdministratorsAreRejected() {
        assertThatThrownBy(() -> photos.adminUpdate(base + 10, metadata("某人", "1", 7, true), admin))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
        // 处理流水线完成时按读到的 version 落库，这里改了版本号那张图就处理失败了。
        assertThatThrownBy(() -> photos.adminUpdate(base + 12, metadata("某人", "1", 1, true), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("处理中");
        assertThatThrownBy(() -> photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10, base + 12), null, null, null, new PhotoService.CampusChange(null), true), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("处理中");

        AuthenticatedUser globalEditor = new AuthenticatedUser(base + 50, "editor", "全局编辑", UserRole.MINISTER,
                null, false, 77L, "EDITOR", "全局编辑组", DataScope.GLOBAL,
                Set.of(PermissionCode.PHOTO_UPLOAD, PermissionCode.PHOTO_VIEW), Set.of());
        assertThatThrownBy(() -> photos.adminUpdate(base + 10, metadata("某人", "1", 1, true), globalEditor))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10), "标题", null, null, null, true), globalEditor))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(photographerOf(base + 10)).isEqualTo("原拍摄者/20240001");
    }

    @Test
    void batchChangesOnlyTheFieldsItWasGiven() {
        PhotoService.BatchMetadataResult result = photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10, base + 11, base + 10), null, null,
                new PhotoService.PhotographerChange(" 批量拍摄者 ", "20990003"),
                new PhotoService.CampusChange(otherCampusId), true), admin);

        assertThat(result.photoCount()).isEqualTo(2);
        assertThat(result.changedCount()).isEqualTo(2);
        assertThat(result.adoptionsUpdated()).isEqualTo(1);
        assertThat(result.previous()).extracting(row -> row.get("id")).containsExactly(base + 10, base + 11);
        for (long id : new long[]{base + 10, base + 11}) {
            Map<String, Object> row = jdbc.sql("SELECT title, description, campus_id, version FROM photo WHERE id=:id")
                    .param("id", id).query().singleRow();
            assertThat(row.get("title")).isEqualTo("原标题" + id);
            assertThat(row.get("description")).isEqualTo("原说明");
            assertThat(((Number) row.get("campus_id")).longValue()).isEqualTo(otherCampusId);
            assertThat(((Number) row.get("version")).intValue()).isEqualTo(2);
            assertThat(photographerOf(id)).isEqualTo("批量拍摄者/20990003");
        }
        assertThat(adoptionPhotographer(base + 10, projectId)).isEqualTo("批量拍摄者/20990003");

        // 已经是这个值的图片不再写库、不涨版本号；「改成无校区」与「不改校区」是两回事。
        PhotoService.BatchMetadataResult again = photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10, base + 11), null, "", null, new PhotoService.CampusChange(null), true), admin);
        assertThat(again.changedCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM photo WHERE id IN (:ids) AND campus_id IS NULL AND description IS NULL")
                .param("ids", List.of(base + 10, base + 11)).query(Long.class).single()).isEqualTo(2);

        PhotoService.BatchMetadataResult noop = photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10, base + 11), null, null,
                new PhotoService.PhotographerChange("批量拍摄者", "20990003"), null, true), admin);
        assertThat(noop.changedCount()).isZero();
        assertThat(jdbc.sql("SELECT version FROM photo WHERE id=:id").param("id", base + 10)
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void batchNeedsSomethingToChangeAndAnExistingCampus() {
        assertThatThrownBy(() -> photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10), null, null, null, null, true), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少选择一项");
        assertThatThrownBy(() -> photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10), "   ", null, null, null, true), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("标题不能为空");
        assertThatThrownBy(() -> photos.adminBatchUpdate(new PhotoService.BatchMetadata(
                List.of(base + 10), null, null, null, new PhotoService.CampusChange(base + 999), true), admin))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.sql("SELECT campus_id FROM photo WHERE id=:id").param("id", base + 10)
                .query(Long.class).single()).isEqualTo(campusId);
    }

    private PhotoService.AdminMetadata metadata(String name, String studentId, int version, boolean sync) {
        return new PhotoService.AdminMetadata("标题", "说明", name, studentId,
                LocalDateTime.of(2024, 5, 1, 8, 0), campusId, List.of(), version, sync);
    }

    private String photographerOf(long photoId) {
        return jdbc.sql("SELECT CONCAT(photographer_name, '/', photographer_student_id) FROM photo WHERE id=:id")
                .param("id", photoId).query(String.class).single();
    }

    private String adoptionPhotographer(long photoId, long project) {
        return jdbc.sql("""
                SELECT CONCAT(photographer_name, '/', photographer_student_id) FROM adoption
                WHERE photo_id=:photo AND project_id=:project
                """).param("photo", photoId).param("project", project).query(String.class).single();
    }

    private void insertPhoto(long id, String status, long uploader) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, description, photographer_student_id, photographer_name, uploaded_by,
                     campus_id, taken_at, tags_json, size, content_type, object_key, sha256, status)
                VALUES (:id, :title, '原说明', '20240001', '原拍摄者', :uploader, :campusId, :takenAt, '[]',
                        1000, 'image/jpeg', :key, :sha, :status)
                """).param("id", id).param("title", "原标题" + id).param("uploader", uploader)
                .param("campusId", campusId).param("takenAt", LocalDateTime.of(2024, 5, 1, 8, 0))
                .param("key", "photos/pam/" + id + ".jpg")
                .param("sha", String.format("%064x", id)).param("status", status).update();
    }

    private void insertAdoption(long photoId, long project, long adoptedBy, boolean deleted) {
        jdbc.sql("""
                INSERT INTO adoption(project_id, photo_id, photographer_student_id, photographer_name,
                                     adopted_by, adopted_at, deleted)
                VALUES (:project, :photo, '20240001', '原拍摄者', :by, :at, :deleted)
                """).param("project", project).param("photo", photoId).param("by", adoptedBy)
                .param("at", LocalDateTime.of(2024, 5, 10, 12, 0)).param("deleted", deleted).update();
    }
}

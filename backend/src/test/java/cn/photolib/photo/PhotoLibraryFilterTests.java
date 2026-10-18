package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图库页新增的筛选条件：拍摄日期（两端含整天）、被引与否、是否进过选题、标签「任意其一」。
 * 「筛选结果全选」就是按这套条件把每一页取回来，所以这里筛错一张，全选就会多选或漏选一张。
 */
@SpringBootTest
@Transactional
class PhotoLibraryFilterTests {
    private static final String KEYWORD = "库筛选夹具";

    @Autowired
    PhotoService photos;
    @Autowired
    JdbcClient jdbc;

    private long base;
    private AuthenticatedUser admin;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & Long.MAX_VALUE >> 4;
        long adminId = base;
        long campusId = base + 1;
        long projectId = base + 2;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '筛选校区')")
                .param("id", campusId).param("code", "plf-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '筛选管理员', 'ADMIN', TRUE, FALSE)
                """).param("id", adminId).param("username", "plf-" + base).update();
        jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (:id, '筛选选题', 'ACTIVE', :u)")
                .param("id", projectId).param("u", adminId).update();
        admin = new AuthenticatedUser(adminId, "plf", "筛选管理员", UserRole.ADMIN, null, false);

        insertPhoto(base + 10, LocalDateTime.of(2024, 5, 1, 0, 0), "[\"合影\"]", adminId, campusId);
        insertPhoto(base + 11, LocalDateTime.of(2024, 5, 3, 23, 59, 59), "[\"颁奖\"]", adminId, campusId);
        insertPhoto(base + 12, LocalDateTime.of(2024, 5, 4, 0, 0), "[\"合影\",\"颁奖\"]", adminId, campusId);
        insertPhoto(base + 13, LocalDateTime.of(2024, 4, 30, 23, 59), "[\"风景\"]", adminId, campusId);

        // 11、12 进了选题相册，其中 12 被引用；13 被引用过但那条记录已撤销（软删）。
        for (long photo : new long[]{base + 11, base + 12}) {
            jdbc.sql("INSERT INTO photo_project(photo_id, project_id) VALUES (:p, :proj)")
                    .param("p", photo).param("proj", projectId).update();
        }
        insertAdoption(base + 12, projectId, adminId, false);
        insertAdoption(base + 13, projectId, adminId, true);
    }

    @Test
    void takenDateRangeIncludesTheWholeOfBothEndDays() {
        assertThat(ids(new PhotoService.LibraryFilter(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 3),
                null, null, false))).containsExactlyInAnyOrder(base + 10, base + 11);
        assertThat(ids(new PhotoService.LibraryFilter(null, LocalDate.of(2024, 4, 30), null, null, false)))
                .containsExactly(base + 13);
        assertThatThrownBy(() -> ids(new PhotoService.LibraryFilter(LocalDate.of(2024, 5, 3),
                LocalDate.of(2024, 5, 1), null, null, false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能晚于");
    }

    @Test
    void adoptionAndProjectMembershipFiltersIgnoreWithdrawnAdoptions() {
        assertThat(ids(new PhotoService.LibraryFilter(null, null, true, null, false)))
                .containsExactly(base + 12);
        assertThat(ids(new PhotoService.LibraryFilter(null, null, false, null, false)))
                .containsExactlyInAnyOrder(base + 10, base + 11, base + 13);
        assertThat(ids(new PhotoService.LibraryFilter(null, null, null, true, false)))
                .containsExactlyInAnyOrder(base + 11, base + 12);
        assertThat(ids(new PhotoService.LibraryFilter(null, null, null, false, false)))
                .containsExactlyInAnyOrder(base + 10, base + 13);
        // 条件之间是「同时满足」：进过选题但没被引用的。
        assertThat(ids(new PhotoService.LibraryFilter(null, null, false, true, false)))
                .containsExactly(base + 11);
    }

    @Test
    void tagsMatchAllByDefaultAndAnyWhenAsked() {
        assertThat(ids(PhotoService.LibraryFilter.NONE, "合影", "颁奖")).containsExactly(base + 12);
        assertThat(ids(new PhotoService.LibraryFilter(null, null, null, null, true), "合影", "颁奖"))
                .containsExactlyInAnyOrder(base + 10, base + 11, base + 12);
        // 含引号的标签不能走 LIKE 粗筛；「任意其一」时整组都不粗筛，结果仍然正确。
        assertThat(ids(new PhotoService.LibraryFilter(null, null, null, null, true), "风景", "带\"引号"))
                .containsExactly(base + 13);
    }

    private List<Long> ids(PhotoService.LibraryFilter filter, String... tags) {
        return photos.list(1, 100, KEYWORD, null, null, null, null, null, null, PhotoStatus.AVAILABLE,
                        false, false, false, List.of(tags), List.of(), filter, admin)
                .items().stream().map(PhotoService.PhotoView::id).toList();
    }

    private void insertPhoto(long id, LocalDateTime takenAt, String tagsJson, long uploader, long campusId) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     campus_id, taken_at, tags_json, size, content_type, object_key, sha256, status)
                VALUES (:id, :title, '20240001', '筛选者', :uploader, :campusId, :takenAt, :tags,
                        1000, 'image/jpeg', :key, :sha, 'AVAILABLE')
                """).param("id", id).param("title", KEYWORD + id).param("uploader", uploader)
                .param("campusId", campusId).param("takenAt", takenAt).param("tags", tagsJson)
                .param("key", "photos/plf/" + id + ".jpg")
                .param("sha", String.format("%064x", id)).update();
    }

    private void insertAdoption(long photoId, long projectId, long adoptedBy, boolean deleted) {
        jdbc.sql("""
                INSERT INTO adoption(project_id, photo_id, photographer_student_id, photographer_name,
                                     adopted_by, adopted_at, deleted)
                VALUES (:project, :photo, '20240001', '筛选者', :by, :at, :deleted)
                """).param("project", projectId).param("photo", photoId).param("by", adoptedBy)
                .param("at", LocalDateTime.of(2024, 5, 10, 12, 0)).param("deleted", deleted).update();
    }
}

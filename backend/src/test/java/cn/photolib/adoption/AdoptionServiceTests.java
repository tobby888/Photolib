package cn.photolib.adoption;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 图片采用服务测试
 * 测试项目采用图片、取消采用、统计排名等功能
 */
@SpringBootTest
@Transactional
class AdoptionServiceTests {
    @Autowired
    private AdoptionService adoptionService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CampusService campusService;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser adminUser;
    private AuthenticatedUser managerUser;
    private ProjectEntity activeProject;
    private ProjectEntity completedProject;
    private CampusEntity testCampus;

    @BeforeEach
    void setUp() {
        testCampus = campusService.create("TEST", "测试校区");

        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (300, 'test-admin', 'hash', '测试管理员', 'ADMIN', null, true, false),
                    (301, 'test-manager', 'hash', '测试负责人', 'CAMPUS_MANAGER', :campusId, true, false)
                """).param("campusId", testCampus.getId()).update();

        adminUser = new AuthenticatedUser(
                300L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);
        managerUser = new AuthenticatedUser(
                301L, "test-manager", "测试负责人", UserRole.CAMPUS_MANAGER, testCampus.getId(), false);

        activeProject = projectService.create(
                "进行中项目", "描述", ProjectStatus.ACTIVE, adminUser);
        completedProject = projectService.create(
                "已完成项目", "描述", ProjectStatus.ACTIVE, adminUser);
        projectService.changeStatus(completedProject.getId(), ProjectStatus.COMPLETED, 1, adminUser);

        // 创建测试照片
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (2001, :projectId, '照片1', '20230001', '张三', 300, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo1.jpg', :sha256_1, 'AVAILABLE'),
                    (2002, :projectId, '照片2', '20230002', '李四', 300, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo2.jpg', :sha256_2, 'AVAILABLE')
                """)
                .param("projectId", activeProject.getId())
                .param("campusId", testCampus.getId())
                .param("sha256_1", "a".repeat(64))
                .param("sha256_2", "b".repeat(64))
                .update();

        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES
                    (3901, :projectId, '已指派需求', :campusId, 1, DATEADD('DAY', 1, CURRENT_TIMESTAMP),
                     'ACCEPTED', :adminId)
                """)
                .param("projectId", activeProject.getId())
                .param("campusId", testCampus.getId())
                .param("adminId", adminUser.id())
                .update();
        jdbc.sql("""
                INSERT INTO request_participant (request_id, user_id, accepted_at)
                VALUES (3901, :userId, CURRENT_TIMESTAMP)
                """)
                .param("userId", managerUser.id())
                .update();
    }

    @Test
    void adoptPhotos_shouldCreateAdoptionRecords() {
        // When: 采用照片
        List<AdoptionEntity> adoptions = adoptionService.adopt(
                activeProject.getId(),
                List.of(2001L, 2002L),
                "用于首页展示",
                adminUser);

        // Then: 应该创建采用记录
        assertThat(adoptions).hasSize(2);
        assertThat(adoptions).allMatch(a -> a.getProjectId().equals(activeProject.getId()));
        assertThat(adoptions).allMatch(a -> a.getRemark().equals("用于首页展示"));
    }

    @Test
    void adoptOwnVisibleGalleryPhoto_asCampusManager_shouldCreateAdoption() {
        jdbc.sql("UPDATE photo SET uploaded_by=:userId WHERE id=2001")
                .param("userId", managerUser.id())
                .update();

        var adoptions = adoptionService.adopt(
                activeProject.getId(), List.of(2001L), "从图库添加", managerUser);

        assertThat(adoptions).singleElement()
                .satisfies(adoption -> assertThat(adoption.getAdoptedBy()).isEqualTo(managerUser.id()));
    }

    @Test
    void adoptInvisibleGalleryPhoto_asCampusManager_shouldBeForbidden() {
        assertThatThrownBy(() -> adoptionService.adopt(
                activeProject.getId(), List.of(2001L), "从图库添加", managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可见");
    }

    @Test
    void adoptPhotoToUnassignedProject_asCampusManager_shouldBeForbidden() {
        var unassignedProject = projectService.create(
                "未指派项目", "描述", ProjectStatus.ACTIVE, adminUser);

        assertThatThrownBy(() -> adoptionService.adopt(
                unassignedProject.getId(), List.of(2001L), "尝试添加", managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    void adoptPhotos_withEmptyList_shouldThrowException() {
        // When & Then: 空列表应该失败
        assertThatThrownBy(() -> adoptionService.adopt(
                activeProject.getId(), List.of(), "备注", adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择");
    }

    @Test
    void adoptPhotos_withTooManyPhotos_shouldThrowException() {
        // When & Then: 超过 200 张应该失败
        List<Long> tooMany = java.util.stream.LongStream.range(1, 202)
                .boxed().toList();

        assertThatThrownBy(() -> adoptionService.adopt(
                activeProject.getId(), tooMany, "备注", adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");
    }

    @Test
    void adoptPhotos_inCompletedProject_shouldThrowException() {
        // When & Then: 已完成项目不能采用照片
        assertThatThrownBy(() -> adoptionService.adopt(
                completedProject.getId(),
                List.of(2001L),
                "备注",
                adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅进行中项目");
    }

    @Test
    void adoptPhotos_duplicatePhoto_shouldThrowException() {
        // Given: 照片已被采用
        adoptionService.adopt(
                activeProject.getId(),
                List.of(2001L),
                "首次采用",
                adminUser);

        // When & Then: 重复采用应该失败
        assertThatThrownBy(() -> adoptionService.adopt(
                activeProject.getId(),
                List.of(2001L),
                "再次采用",
                adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被该项目采用");
    }

    @Test
    void cancelAdoption_shouldMarkAsDeleted() {
        // Given: 已采用的照片
        List<AdoptionEntity> adoptions = adoptionService.adopt(
                activeProject.getId(),
                List.of(2001L),
                "备注",
                adminUser);

        // When: 取消采用
        adoptionService.cancel(activeProject.getId(), adoptions.get(0).getId());

        // Then: 采用记录应该被删除
        var remaining = adoptionService.list(activeProject.getId(), 1, 20, null);
        assertThat(remaining.items()).isEmpty();
    }

    @Test
    void cancelAdoption_inCompletedProject_shouldThrowException() {
        // Given: 已完成项目中的采用记录
        jdbc.sql("""
                INSERT INTO adoption
                    (id, project_id, photo_id, photographer_student_id, photographer_name,
                     adopted_by, adopted_at)
                VALUES
                    (3001, :projectId, 2001, '20230001', '张三', 300, NOW())
                """)
                .param("projectId", completedProject.getId())
                .update();

        // When & Then: 已完成项目不能取消采用
        assertThatThrownBy(() -> adoptionService.cancel(completedProject.getId(), 3001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已完成项目");
    }

    @Test
    void listAdoptions_shouldReturnProjectAdoptions() {
        // Given: 多个项目的采用记录
        adoptionService.adopt(activeProject.getId(), List.of(2001L), "项目1采用", adminUser);

        var anotherProject = projectService.create(
                "另一个项目", "描述", ProjectStatus.ACTIVE, adminUser);
        adoptionService.adopt(anotherProject.getId(), List.of(2002L), "项目2采用", adminUser);

        // When: 查询特定项目的采用记录
        var project1Adoptions = adoptionService.list(activeProject.getId(), 1, 20, null);
        var project2Adoptions = adoptionService.list(anotherProject.getId(), 1, 20, null);

        // Then: 应该只返回该项目的采用记录
        assertThat(project1Adoptions.items()).hasSize(1);
        assertThat(project1Adoptions.items().get(0).getPhotoId()).isEqualTo(2001L);

        assertThat(project2Adoptions.items()).hasSize(1);
        assertThat(project2Adoptions.items().get(0).getPhotoId()).isEqualTo(2002L);
    }

    @Test
    void ranking_shouldReturnPhotographerStats() {
        // Given: 多个摄影师的采用记录
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (2003, :projectId, '照片3', '20230001', '张三', 300, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo3.jpg', :sha256, 'AVAILABLE')
                """)
                .param("projectId", activeProject.getId())
                .param("campusId", testCampus.getId())
                .param("sha256", "c".repeat(64))
                .update();

        adoptionService.adopt(activeProject.getId(), List.of(2001L, 2003L), "张三的照片", adminUser);
        adoptionService.adopt(activeProject.getId(), List.of(2002L), "李四的照片", adminUser);

        // When: 查询排名
        var ranking = adoptionService.ranking(null, null, activeProject.getId(), null);

        // Then: 应该按采用数量排序
        assertThat(ranking).isNotEmpty();
        var topPhotographer = ranking.get(0);
        assertThat(topPhotographer.photographerStudentId()).isEqualTo("20230001");
        assertThat(topPhotographer.photographerName()).isEqualTo("张三");
        assertThat(topPhotographer.adoptedCount()).isEqualTo(2);
    }

    @Test
    void adoptPhotos_shouldCreatePhotoProjectLinks() {
        // Given: 创建另一个项目，准备从图库添加图片
        var targetProject = projectService.create(
                "目标项目", "描述", ProjectStatus.ACTIVE, adminUser);

        // When: 从图库添加图片到目标项目
        adoptionService.adopt(targetProject.getId(), List.of(2001L, 2002L), "从图库添加", adminUser);

        // Then: 应该在 photo_project 表中创建关联
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM photo_project
                WHERE project_id = :projectId AND photo_id IN (2001, 2002)
                """)
                .param("projectId", targetProject.getId())
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(2);
    }

    @Test
    void cancelAdoption_shouldRemovePhotoProjectLink_whenNoOtherLinks() {
        // Given: 创建一个新项目，从图库添加图片
        var targetProject = projectService.create(
                "目标项目", "描述", ProjectStatus.ACTIVE, adminUser);
        var adoptions = adoptionService.adopt(
                targetProject.getId(), List.of(2001L), "从图库添加", adminUser);

        // When: 取消采用
        adoptionService.cancel(targetProject.getId(), adoptions.get(0).getId());

        // Then: photo_project 表中的关联应该被删除
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM photo_project
                WHERE project_id = :projectId AND photo_id = 2001
                """)
                .param("projectId", targetProject.getId())
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(0);
    }

    @Test
    void cancelAdoption_shouldKeepPhotoProjectLink_whenPhotoOriginallyFromProject() {
        // Given: 图片2001原本就属于activeProject（通过upload创建）
        // 先在 photo_project 表中建立原始关联
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (2001, :projectId)")
                .param("projectId", activeProject.getId())
                .update();

        // 然后采用这张照片
        var adoptions = adoptionService.adopt(
                activeProject.getId(), List.of(2001L), "采用自己项目的图片", adminUser);

        // When: 取消采用
        adoptionService.cancel(activeProject.getId(), adoptions.get(0).getId());

        // Then: photo_project 表中的关联应该保留（因为图片本身就属于这个项目）
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM photo_project
                WHERE project_id = :projectId AND photo_id = 2001
                """)
                .param("projectId", activeProject.getId())
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(1);
    }
}

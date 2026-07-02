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
    private ProjectEntity activeProject;
    private ProjectEntity completedProject;
    private CampusEntity testCampus;

    @BeforeEach
    void setUp() {
        testCampus = campusService.create("TEST", "测试校区");

        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (300, 'test-admin', 'hash', '测试管理员', 'ADMIN', true, false)
                """).update();

        adminUser = new AuthenticatedUser(
                300L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);

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
}

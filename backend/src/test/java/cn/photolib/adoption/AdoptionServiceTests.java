package cn.photolib.adoption;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
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
import java.util.Set;

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
        jdbc.sql("""
                INSERT INTO photo_project (photo_id, project_id)
                VALUES (2001, :projectId), (2002, :projectId)
                """)
                .param("projectId", activeProject.getId())
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
        projectService.addPhotos(anotherProject.getId(), List.of(2002L), adminUser);
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
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (2003, :projectId)")
                .param("projectId", activeProject.getId())
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

        var otherCampus = campusService.create("RANK-OTHER", "排名权限外校区");
        var scopedStatisticsUser = new AuthenticatedUser(
                -88L, "scoped-statistics", "校区统计员", UserRole.CAMPUS_MANAGER,
                otherCampus.getId(), false, -88L, "SCOPED_STATISTICS", "校区统计员",
                DataScope.CAMPUS, Set.of(PermissionCode.STATISTICS_DOWNLOAD), Set.of(otherCampus.getId()));
        assertThat(adoptionService.ranking(null, null, activeProject.getId(), null, scopedStatisticsUser))
                .isEmpty();
        assertThatThrownBy(() -> adoptionService.ranking(
                null, null, activeProject.getId(), testCampus.getId(), scopedStatisticsUser))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权查看该校区");
    }

    @Test
    void photographers_shouldAggregateAdoptedPhotosByAuthor() {
        // Given: 张三两张被引、李四一张被引，再加一张在相册里但没被引的图片
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (2004, :projectId, '照片4', '20230001', '张三', 300, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo4.jpg', :sha256_4, 'AVAILABLE'),
                    (2005, :projectId, '照片5', '20230003', '王五', 300, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/photo5.jpg', :sha256_5, 'AVAILABLE')
                """)
                .param("projectId", activeProject.getId())
                .param("campusId", testCampus.getId())
                .param("sha256_4", "d".repeat(64))
                .param("sha256_5", "e".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO photo_project (photo_id, project_id)
                VALUES (2004, :projectId), (2005, :projectId)
                """).param("projectId", activeProject.getId()).update();
        adoptionService.adopt(activeProject.getId(), List.of(2001L, 2004L, 2002L), "被引", adminUser);

        var photographers = adoptionService.photographers(activeProject.getId(), adminUser);

        // Then: 只有被引的图片进名单，按张数从多到少；没被引的王五不出现
        assertThat(photographers).hasSize(2);
        assertThat(photographers.get(0).photographerName()).isEqualTo("张三");
        assertThat(photographers.get(0).photographerStudentId()).isEqualTo("20230001");
        assertThat(photographers.get(0).adoptedCount()).isEqualTo(2);
        assertThat(photographers.get(1).photographerName()).isEqualTo("李四");
        assertThat(photographers.get(1).adoptedCount()).isEqualTo(1);
        assertThat(photographers).noneMatch(item -> "王五".equals(item.photographerName()));
    }

    @Test
    void photographers_shouldFollowTheCurrentPhotographerAndDropCancelledAdoptions() {
        var adoptions = adoptionService.adopt(
                activeProject.getId(), List.of(2001L, 2002L), "被引", adminUser);

        // Given: 采用之后把 2001 的拍摄者改成别人（PhotoService.update 允许），并取消 2002 的被引
        jdbc.sql("""
                UPDATE photo SET photographer_student_id='20230009', photographer_name='改后的人'
                WHERE id=2001
                """).update();
        var cancelled = adoptions.stream()
                .filter(adoption -> adoption.getPhotoId().equals(2002L)).findFirst().orElseThrow();
        adoptionService.cancel(activeProject.getId(), cancelled.getId());

        var photographers = adoptionService.photographers(activeProject.getId(), adminUser);

        // Then: 名单跟着图片上现在的拍摄者走，而不是 adoption 行里的快照；取消的被引不算
        assertThat(photographers).singleElement().satisfies(item -> {
            assertThat(item.photographerName()).isEqualTo("改后的人");
            assertThat(item.photographerStudentId()).isEqualTo("20230009");
            assertThat(item.adoptedCount()).isEqualTo(1);
        });
    }

    @Test
    void photographers_shouldExcludeAuthorsOutsideCampusScope() {
        // Given: 校区范围账号参与了本选题的一个需求（选题可见），但被引图片都是其他校区的
        var otherCampus = campusService.create("AUTHOR-OTHER", "作者范围外校区");
        adoptionService.adopt(activeProject.getId(), List.of(2001L), "他人上传的图片", adminUser);
        jdbc.sql("""
                INSERT INTO photo_request (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (7200, :projectId, '参与需求', :campusId, NOW(), 'ACCEPTED', 300)
                """).param("projectId", activeProject.getId())
                .param("campusId", otherCampus.getId()).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (304, 'author-scoped', 'hash', '范围内负责人', 'CAMPUS_MANAGER', :campusId, true, false)
                """).param("campusId", otherCampus.getId()).update();
        jdbc.sql("""
                INSERT INTO request_participant (request_id, user_id, accepted_at)
                VALUES (7200, 304, NOW())
                """).update();
        var scoped = new AuthenticatedUser(304L, "author-scoped", "范围内负责人",
                UserRole.CAMPUS_MANAGER, otherCampus.getId(), false, -72L, "SCOPED_AUTHORS",
                "校区被引组", DataScope.CAMPUS,
                Set.of(PermissionCode.PROJECT_VIEW), Set.of(otherCampus.getId()));

        // Then: 与读采用记录同一条规则——选题可见不等于能读到其他校区图片的作者
        assertThat(adoptionService.photographers(activeProject.getId(), scoped)).isEmpty();
        assertThat(adoptionService.photographers(activeProject.getId(), adminUser)).hasSize(1);
    }

    @Test
    void photographers_shouldStayEmptyForCampusScopedAccountWithoutAuthorizedCampus() {
        // Given: 数据范围为校区但还没补授权校区的账号（权限组刚从 GLOBAL 改成 CAMPUS）。
        // 这种账号靠「参与需求」看不到任何选题，但选片人那条路是无条件放行的（requireVisible
        // 对 project_selector 直接 return），所以它确实能走到名单这一步。
        adoptionService.adopt(activeProject.getId(), List.of(2001L), "被引", adminUser);
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (305, 'author-unscoped', 'hash', '未授权校区账号', 'CAMPUS_MANAGER', null, true, false)
                """).update();
        jdbc.sql("""
                INSERT INTO project_selector (project_id, user_id, created_by)
                VALUES (:projectId, 305, 300)
                """).param("projectId", activeProject.getId()).update();
        var unscoped = new AuthenticatedUser(305L, "author-unscoped", "未授权校区账号",
                UserRole.CAMPUS_MANAGER, null, false, -73L, "PENDING_CAMPUS",
                "待补校区组", DataScope.CAMPUS, Set.of(PermissionCode.PROJECT_VIEW), Set.of());

        // Then: 空授权集合不能被当成「全局范围」放行全量名单
        assertThat(adoptionService.photographers(activeProject.getId(), unscoped)).isEmpty();
    }

    @Test
    void photographers_shouldRequireProjectVisibility() {
        var unassignedProject = projectService.create("未指派项目", "描述", ProjectStatus.ACTIVE, adminUser);
        var scoped = new AuthenticatedUser(managerUser.id(), "test-manager", "测试负责人",
                UserRole.CAMPUS_MANAGER, testCampus.getId(), false, -74L, "SCOPED_AUTHORS2",
                "校区被引组", DataScope.CAMPUS,
                Set.of(PermissionCode.PROJECT_VIEW), Set.of(testCampus.getId()));

        assertThatThrownBy(() -> adoptionService.photographers(unassignedProject.getId(), scoped))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    void adoptPhotos_outsideProjectAlbum_shouldThrowException() {
        // Given: 图片尚未加入目标项目相册
        var targetProject = projectService.create(
                "目标项目", "描述", ProjectStatus.ACTIVE, adminUser);

        // When & Then: 标记引用不能隐式把图片加入项目
        assertThatThrownBy(() -> adoptionService.adopt(
                targetProject.getId(), List.of(2001L), "尝试直接引用", adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未加入项目相册");
    }

    @Test
    void adoptionListAndCancel_shouldExcludeRecordsOutsideCampusScope() {
        // Given: 校区负责人参与了本选题的一个需求（因此选题对其可见），
        // 但选题里还有其他校区/其他人上传图片产生的被引记录。
        var otherCampus = campusService.create("ADOPT-OTHER", "被引范围外校区");
        projectService.addPhotos(activeProject.getId(), List.of(2001L, 2002L), adminUser);
        var foreignAdoptions = adoptionService.adopt(
                activeProject.getId(), List.of(2001L, 2002L), "他人上传的图片", adminUser);
        jdbc.sql("""
                INSERT INTO photo_request (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (7100, :projectId, '参与需求', :campusId, NOW(), 'ACCEPTED', 300)
                """).param("projectId", activeProject.getId())
                .param("campusId", otherCampus.getId()).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (302, 'adopt-scoped', 'hash', '范围内负责人', 'CAMPUS_MANAGER', :campusId, true, false)
                """).param("campusId", otherCampus.getId()).update();
        jdbc.sql("""
                INSERT INTO request_participant (request_id, user_id, accepted_at)
                VALUES (7100, 302, NOW())
                """).update();
        var scoped = new AuthenticatedUser(302L, "adopt-scoped", "范围内负责人",
                UserRole.CAMPUS_MANAGER, otherCampus.getId(), false, -70L, "SCOPED_ADOPT",
                "校区被引组", DataScope.CAMPUS,
                Set.of(PermissionCode.PROJECT_VIEW, PermissionCode.PROJECT_ADOPT),
                Set.of(otherCampus.getId()));

        // Then: 选题可见不等于能读到/取消其他校区图片的被引记录
        assertThat(adoptionService.list(activeProject.getId(), 1, 50, null, scoped).items())
                .isEmpty();
        assertThatThrownBy(() -> adoptionService.cancel(
                activeProject.getId(), foreignAdoptions.get(0).getId(), scoped))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权取消该图片的被引标记");
    }

    @Test
    void adoptionRanking_shouldStayEmptyForCampusScopedAccountWithoutAuthorizedCampus() {
        // Given: 数据范围为校区但尚未补授权校区的账号（例如权限组刚从 GLOBAL 改成 CAMPUS）
        projectService.addPhotos(activeProject.getId(), List.of(2001L), adminUser);
        adoptionService.adopt(activeProject.getId(), List.of(2001L), "张三的照片", adminUser);
        var unscoped = new AuthenticatedUser(303L, "adopt-unscoped", "未授权校区账号",
                UserRole.CAMPUS_MANAGER, null, false, -71L, "PENDING_CAMPUS",
                "待补校区组", DataScope.CAMPUS,
                Set.of(PermissionCode.STATISTICS_DOWNLOAD), Set.of());

        // Then: 空授权集合不能被当成"全局范围"而放行全量统计
        assertThat(adoptionService.ranking(null, null, activeProject.getId(), null, unscoped))
                .isEmpty();
    }

    @Test
    void cancelAdoption_shouldKeepPhotoProjectLink_whenAddedFromGallery() {
        // Given: 创建一个新项目，从图库添加并采用图片
        var targetProject = projectService.create(
                "目标项目", "描述", ProjectStatus.ACTIVE, adminUser);
        projectService.addPhotos(targetProject.getId(), List.of(2001L), adminUser);
        var adoptions = adoptionService.adopt(
                targetProject.getId(), List.of(2001L), "从图库添加", adminUser);

        // When: 取消采用
        adoptionService.cancel(targetProject.getId(), adoptions.get(0).getId());

        // Then: 取消采用只改变引用状态，图片仍应留在项目相册
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM photo_project
                WHERE project_id = :projectId AND photo_id = 2001
                """)
                .param("projectId", targetProject.getId())
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void cancelAdoption_shouldKeepPhotoProjectLink_whenPhotoOriginallyFromProject() {
        // Given: 图片2001原本就属于activeProject（通过upload创建）
        // photo_project 原始关联已在测试准备阶段建立，然后采用这张照片
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

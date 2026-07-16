package cn.photolib.request;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.error.BusinessException;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * 图片需求服务测试
 * 测试需求创建、接受、取消等功能
 */
@SpringBootTest
@Transactional
class RequestServiceTests {
    @Autowired
    private RequestService requestService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CampusService campusService;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser adminUser;
    private AuthenticatedUser ministerUser;
    private AuthenticatedUser otherMinisterUser;
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
                    (400, 'test-admin', 'hash', '测试管理员', 'ADMIN', null, true, false),
                    (401, 'test-minister', 'hash', '测试部长', 'MINISTER', null, true, false),
                    (402, 'test-manager', 'hash', '测试负责人', 'CAMPUS_MANAGER', :campusId, true, false),
                    (403, 'other-minister', 'hash', '其他部长', 'MINISTER', null, true, false)
                """).param("campusId", testCampus.getId()).update();

        adminUser = new AuthenticatedUser(
                400L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);
        ministerUser = new AuthenticatedUser(
                401L, "test-minister", "测试部长", UserRole.MINISTER, null, false);
        otherMinisterUser = new AuthenticatedUser(
                403L, "other-minister", "其他部长", UserRole.MINISTER, null, false);
        managerUser = new AuthenticatedUser(
                402L, "test-manager", "测试负责人", UserRole.CAMPUS_MANAGER, testCampus.getId(), false);

        activeProject = projectService.create(
                "进行中项目", "描述", ProjectStatus.ACTIVE, adminUser);
        completedProject = projectService.create(
                "已完成项目", "描述", ProjectStatus.ACTIVE, adminUser);
        projectService.changeStatus(completedProject.getId(), ProjectStatus.COMPLETED, 1, adminUser);
    }

    @Test
    void createRequest_shouldReturnRequestWithPendingStatus() {
        // When: 创建图片需求
        RequestService.CreateCommand command = new RequestService.CreateCommand(
                "校园活动照片", "需要拍摄开学典礼现场照片",
                testCampus.getId(), 10, LocalDateTime.now().plusDays(7));

        var request = requestService.create(activeProject.getId(), command, ministerUser);

        // Then: 需求应该被创建
        assertThat(request.getProjectId()).isEqualTo(activeProject.getId());
        assertThat(request.getTitle()).isEqualTo("校园活动照片");
        assertThat(request.getCampusId()).isEqualTo(testCampus.getId());
        assertThat(request.getRequiredCount()).isEqualTo(10);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.DRAFT);
    }

    @Test
    void createRequest_withoutRequiredCount_shouldAllowUnlimitedUploads() {
        RequestService.CreateCommand command = new RequestService.CreateCommand(
                "无限制交付需求", "校区负责人可按实际情况上传图片",
                testCampus.getId(), null, LocalDateTime.now().plusDays(7));

        var request = requestService.create(activeProject.getId(), command, ministerUser);

        assertThat(request.getRequiredCount()).isNull();
        assertThat(request.getStatus()).isEqualTo(RequestStatus.DRAFT);
    }

    @Test
    void createRequest_inCompletedProject_shouldThrowException() {
        // When & Then: 已完成项目不能创建需求
        RequestService.CreateCommand command = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));

        assertThatThrownBy(() -> requestService.create(
                completedProject.getId(), command, ministerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已结束项目不能创建需求");
    }

    @Test
    void updateRequest_shouldModifyRequestInfo() {
        // Given: 已存在的需求
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "原始标题", "原始描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);

        // When: 更新需求
        RequestService.CreateCommand update = new RequestService.CreateCommand(
                "新标题", "新描述", testCampus.getId(), 10,
                LocalDateTime.now().plusDays(3));
        var updated = requestService.update(request.getId(), update, 1, ministerUser);

        // Then: 信息应该被更新
        assertThat(updated.getTitle()).isEqualTo("新标题");
        assertThat(updated.getDescription()).isEqualTo("新描述");
        assertThat(updated.getRequiredCount()).isEqualTo(10);
    }

    @Test
    void acceptRequest_shouldAddParticipantAndUpdateStatus() {
        // Given: 已发布的需求
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), 1, ministerUser);

        // When: 校区负责人接受需求
        requestService.accept(request.getId(), managerUser);

        // Then: 需求状态应该变为已接受
        var accepted = requestService.get(request.getId());
        assertThat(accepted.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(accepted.getFirstAcceptedAt()).isNotNull();

        // 应该添加参与人记录
        var participants = requestService.participants(request.getId());
        assertThat(participants).hasSize(1);
        assertThat(participants.get(0).getUserId()).isEqualTo(managerUser.id());
    }

    @Test
    void acceptRequest_byWrongCampus_shouldThrowException() {
        // Given: 其他校区的需求
        var anotherCampus = campusService.create("OTHER", "其他校区");
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "其他校区需求", "描述", anotherCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), 1, ministerUser);

        // When & Then: 不同校区的负责人不能接受
        assertThatThrownBy(() -> requestService.accept(request.getId(), managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能接受所属校区的需求");
    }

    @Test
    void acceptRequest_twice_shouldThrowException() {
        // Given: 已接受的需求
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), 1, ministerUser);
        requestService.accept(request.getId(), managerUser);

        // When & Then: 同一个人再次接受应该失败
        assertThatThrownBy(() -> requestService.accept(request.getId(), managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已接受该需求");
    }

    @Test
    void leaveRequest_shouldRemoveParticipant() {
        // Given: 已接受的需求
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), 1, ministerUser);
        requestService.accept(request.getId(), managerUser);

        // When: 退出需求
        requestService.leave(request.getId(), managerUser);

        // Then: 参与人应该被移除
        var participants = requestService.participants(request.getId());
        assertThat(participants).isEmpty();

        // 需求状态应该变回已发布
        var updated = requestService.get(request.getId());
        assertThat(updated.getStatus()).isEqualTo(RequestStatus.PUBLISHED);
    }

    @Test
    void cancelRequest_shouldChangeStatusToCancelled() {
        // Given: 已发布的需求
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), 1, ministerUser);

        // When: 取消需求
        var cancelled = requestService.cancel(
                request.getId(), "活动取消", 2, ministerUser);

        // Then: 状态应该变为已取消
        assertThat(cancelled.getStatus()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo("活动取消");
    }

    @Test
    void completeRequest_shouldChangeStatusToCompleted() {
        // Given: 已提交的需求（需要先发布、接受、提交）
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), 1, ministerUser);
        requestService.accept(request.getId(), managerUser);

        // 添加可用照片以便提交
        jdbc.sql("""
                INSERT INTO photo
                    (request_id, project_id, title, photographer_student_id, photographer_name,
                     uploaded_by, campus_id, taken_at, size, content_type, object_key, sha256, status)
                VALUES
                    (:reqId, :projectId, '测试照片', '20230001', '张三', :userId, :campusId,
                     NOW(), 1000, 'image/jpeg', 'photos/2026/test.jpg', :sha256, 'AVAILABLE')
                """)
                .param("reqId", request.getId())
                .param("projectId", activeProject.getId())
                .param("userId", managerUser.id())
                .param("campusId", testCampus.getId())
                .param("sha256", "a".repeat(64))
                .update();

        int submitVersion = requestService.get(request.getId()).getVersion();
        requestService.submit(request.getId(), submitVersion, managerUser);

        // When: 完成需求
        int completeVersion = requestService.get(request.getId()).getVersion();
        var completed = requestService.complete(request.getId(), completeVersion);

        // Then: 状态应该变为已完成
        assertThat(completed.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    void returnForRevision_byMinisterAndAdmin_shouldNotifyManagerAndAllowResubmission() {
        PhotoRequestEntity submitted = createSubmittedRequest();

        var returnedByMinister = requestService.returnForRevision(
                submitted.getId(), "请补充全景照片", submitted.getVersion(), ministerUser);

        assertThat(returnedByMinister.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(returnedByMinister.getReturnReason()).isEqualTo("请补充全景照片");
        assertThat(returnedByMinister.getReturnedBy()).isEqualTo(ministerUser.id());
        assertThat(returnedByMinister.getReturnedAt()).isNotNull();
        assertThat(returnNotificationCount()).isEqualTo(1);

        var resubmitted = requestService.submit(
                returnedByMinister.getId(), returnedByMinister.getVersion(), managerUser);
        assertThat(resubmitted.getReturnReason()).isNull();
        assertThat(resubmitted.getReturnedBy()).isNull();
        assertThat(resubmitted.getReturnedAt()).isNull();
        var returnedByAdmin = requestService.returnForRevision(
                resubmitted.getId(), "请校正图片说明", resubmitted.getVersion(), adminUser);

        assertThat(returnedByAdmin.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(returnedByAdmin.getReturnReason()).isEqualTo("请校正图片说明");
        assertThat(returnedByAdmin.getReturnedBy()).isEqualTo(adminUser.id());
        assertThat(returnNotificationCount()).isEqualTo(2);
    }

    @Test
    void returnForRevision_whenNotSubmitted_shouldThrowException() {
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "执行中需求", "描述", testCampus.getId(), null,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        requestService.publish(request.getId(), requestService.get(request.getId()).getVersion(), ministerUser);
        var accepted = requestService.accept(request.getId(), managerUser);

        assertThatThrownBy(() -> requestService.returnForRevision(
                accepted.getId(), "需要修改", accepted.getVersion(), ministerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅待确认需求可打回");
    }

    @Test
    void returnForRevision_byCampusManager_shouldBeForbidden() {
        PhotoRequestEntity submitted = createSubmittedRequest();

        assertThatThrownBy(() -> requestService.returnForRevision(
                submitted.getId(), "自行打回", submitted.getVersion(), managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅管理员或部长可打回需求");
    }

    @Test
    void listRequests_asCampusManager_shouldFilterByCampus() {
        // Given: 多个校区的需求
        var anotherCampus = campusService.create("OTHER", "其他校区");

        RequestService.CreateCommand myCampusRequest = new RequestService.CreateCommand(
                "我的校区需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var mine = requestService.create(activeProject.getId(), myCampusRequest, ministerUser);
        requestService.publish(mine.getId(), requestService.get(mine.getId()).getVersion(), ministerUser);

        RequestService.CreateCommand otherCampusRequest = new RequestService.CreateCommand(
                "其他校区需求", "描述", anotherCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var other = requestService.create(activeProject.getId(), otherCampusRequest, ministerUser);
        requestService.publish(other.getId(), requestService.get(other.getId()).getVersion(), ministerUser);

        // When: 校区负责人查询需求
        var result = requestService.list(
                1, 20, activeProject.getId(), null, null, null, managerUser);

        // Then: 应该只看到自己校区的需求
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).getTitle()).isEqualTo("我的校区需求");
    }

    @Test
    void campusManager_shouldNotSeeDraftOrUnrelatedRequestDetails() {
        var draft = requestService.create(activeProject.getId(), new RequestService.CreateCommand(
                "内部草稿", "尚未发布", testCampus.getId(), null,
                LocalDateTime.now().plusDays(1)), ministerUser);

        assertThat(requestService.list(1, 20, null, null, null, null, managerUser).items())
                .extracting(PhotoRequestEntity::getId).doesNotContain(draft.getId());
        assertThatThrownBy(() -> requestService.get(draft.getId(), managerUser))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权查看");
        assertThatThrownBy(() -> requestService.participants(draft.getId(), managerUser))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权查看");
    }

    @Test
    void minister_shouldReturnAnotherCreatorsRequest() {
        PhotoRequestEntity submitted = createSubmittedRequest();

        var returned = requestService.returnForRevision(
                submitted.getId(), "请补充交付内容", submitted.getVersion(), otherMinisterUser);

        assertThat(returned.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(returned.getReturnReason()).isEqualTo("请补充交付内容");
        assertThat(returned.getReturnedBy()).isEqualTo(otherMinisterUser.id());
        assertThat(returnNotificationCount()).isEqualTo(1);
    }

    @Test
    void deleteRequest_shouldMarkAsDeleted() {
        // Given: 已存在的需求
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);

        // When: 管理员删除需求
        requestService.delete(request.getId(), adminUser);

        // Then: 需求应该被逻辑删除
        assertThatThrownBy(() -> requestService.get(request.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("需求不存在");
    }

    @Test
    void deleteRequest_byNonAdmin_shouldBeForbidden() {
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "测试需求", "描述", testCampus.getId(), 5,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);

        assertThatThrownBy(() -> requestService.delete(request.getId(), ministerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅管理员可删除需求");
        assertThat(requestService.get(request.getId())).isNotNull();
    }

    private PhotoRequestEntity createSubmittedRequest() {
        RequestService.CreateCommand create = new RequestService.CreateCommand(
                "待确认需求", "描述", testCampus.getId(), null,
                LocalDateTime.now().plusDays(1));
        var request = requestService.create(activeProject.getId(), create, ministerUser);
        var published = requestService.publish(
                request.getId(), requestService.get(request.getId()).getVersion(), ministerUser);
        var accepted = requestService.accept(published.getId(), managerUser);
        return requestService.submit(accepted.getId(), accepted.getVersion(), managerUser);
    }

    private long returnNotificationCount() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id=:userId AND event_type='REQUEST_RETURNED'
                  AND content LIKE :requestTitle
                """)
                .param("userId", managerUser.id())
                .param("requestTitle", "%待确认需求%")
                .query(Long.class)
                .single();
    }
}

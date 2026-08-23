package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.recruitment.model.RecruitmentTaskStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class RecruitmentControllerSecurityTests {

    @Autowired
    private RecruitmentTaskController taskController;
    @Autowired
    private RecruitmentApplicationController applicationController;
    @MockitoBean
    private RecruitmentTaskService taskService;
    @MockitoBean
    private RecruitmentApplicationService applicationService;

    private final AuthenticatedUser principal = new AuthenticatedUser(
            1L, "recruitment-security", "招募安全测试", UserRole.MINISTER, null, false);

    @Test
    @WithMockUser(authorities = "RECRUITMENT_VIEW")
    void viewAuthorityCannotEnterTaskMutationMethods() {
        assertThatThrownBy(() -> taskController.create(null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> taskController.update(11L, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> taskController.publish(11L, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> taskController.close(11L, null, principal))
                .isInstanceOf(AccessDeniedException.class);

        // Method security must reject the request before parameters are dereferenced
        // or either underlying service is invoked.
        verifyNoInteractions(taskService, applicationService);
    }

    @Test
    @WithMockUser(authorities = "RECRUITMENT_PUBLISH")
    void publishAuthorityCanEnterTaskMutationMethods() {
        RecruitmentTaskController.TaskRequest create = taskRequest();
        RecruitmentTaskController.UpdateTaskRequest update = updateTaskRequest();
        RecruitmentTaskController.VersionRequest version = new RecruitmentTaskController.VersionRequest(3);

        taskController.create(create, principal);
        taskController.update(11L, update, principal);
        taskController.publish(11L, version, principal);
        taskController.close(11L, version, principal);

        verify(taskService).create(any(RecruitmentTaskService.TaskCommand.class), same(principal));
        verify(taskService).update(eq(11L), any(RecruitmentTaskService.TaskCommand.class),
                eq(3), same(principal));
        verify(taskService).publish(11L, 3, principal);
        verify(taskService).close(11L, 3, principal);
    }

    @Test
    @WithMockUser(authorities = "RECRUITMENT_PUBLISH")
    void publishAuthorityAloneCannotEnterTaskOrApplicationReadMethods() {
        assertThatThrownBy(() -> taskController.list(1, 20, null, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> taskController.get(11L, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> taskController.applications(11L, 1, 20, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> applicationController.get("application-id", principal))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(taskService, applicationService);
    }

    @Test
    @WithMockUser(authorities = "RECRUITMENT_VIEW")
    void viewAuthorityCanEnterTaskAndApplicationReadMethods() {
        taskController.list(2, 25, "新人", RecruitmentTaskStatus.DRAFT, principal);
        taskController.get(11L, principal);
        taskController.applications(11L, 3, 30, "2023001", principal);
        applicationController.get("application-id", principal);

        verify(taskService).list(2, 25, "新人", RecruitmentTaskStatus.DRAFT, principal);
        verify(taskService).get(11L, principal);
        verify(applicationService).list(11L, 3, 30, "2023001", principal);
        verify(applicationService).get("application-id", principal);
    }

    private static RecruitmentTaskController.TaskRequest taskRequest() {
        return new RecruitmentTaskController.TaskRequest(
                "秋季招募", "介绍", new RecruitmentFormSchema(List.of()),
                "学号", null, "作品", null, false,
                LocalDateTime.of(2030, 9, 1, 0, 0),
                LocalDateTime.of(2030, 9, 30, 0, 0));
    }

    private static RecruitmentTaskController.UpdateTaskRequest updateTaskRequest() {
        return new RecruitmentTaskController.UpdateTaskRequest(
                "秋季招募", "更新介绍", new RecruitmentFormSchema(List.of()),
                "学号", null, "作品", null, false,
                LocalDateTime.of(2030, 9, 1, 0, 0),
                LocalDateTime.of(2030, 10, 7, 0, 0), 3);
    }
}

package cn.photolib.feedback;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 反馈接口的权限边界（对齐 {@code DocControllerSecurityTests}）。
 *
 * <p>反馈面只有一条方法级安全规则：改状态要 {@code role=ADMIN}
 * （{@code hasRole('ADMIN')}，即权限组 code 为 ADMIN）。其余端点
 * （提交 / 列表 / 详情 / 回复）对「任何已登录成员」开放，谁能看哪条由
 * {@link FeedbackService} 按「ADMIN 或提交人」裁剪（见 {@link FeedbackServiceTests}）。
 * 因此这里只锁方法级那道门：非 ADMIN 与匿名一律拒绝，且**在解引用参数、调用
 * Service 之前**就被拒绝。</p>
 */
@SpringBootTest
class FeedbackControllerSecurityTests {

    @Autowired private FeedbackController controller;
    @MockitoBean private FeedbackService service;

    private final AuthenticatedUser principal = new AuthenticatedUser(
            901L, "feedback-security", "反馈安全测试", UserRole.ADMIN, null, false);

    @Test
    @WithMockUser(roles = "CAMPUS_MANAGER")
    void statusChangeRejectsCallersWithoutAdminRole() {
        assertThatThrownBy(() -> controller.changeStatus(
                7L, new FeedbackController.StatusRequest("IN_PROGRESS", 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    @WithAnonymousUser
    void statusChangeRejectsAnonymous() {
        assertThatThrownBy(() -> controller.changeStatus(
                7L, new FeedbackController.StatusRequest("RESOLVED", 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayChangeStatus() {
        controller.changeStatus(7L, new FeedbackController.StatusRequest("RESOLVED", 3), principal);
        verify(service).changeStatus(7L, "RESOLVED", 3, principal);
    }

    @Test
    @WithMockUser(roles = "CAMPUS_MANAGER")
    void submitListGetAndReplyAreOpenToAnySignedInMember() {
        // 这几条端点上没有 @PreAuthorize——"能进系统的登录成员"即可，
        // 具体可见范围由 Service 裁。非 ADMIN 因此不该被方法安全挡住。
        controller.submit(new FeedbackController.SubmitRequest("标题", "ISSUE", "<p>正文</p>"), principal);
        controller.list(null, principal);
        controller.get(7L, principal);
        controller.reply(7L, new FeedbackController.ReplyRequest("<p>补充</p>"), principal);
        verify(service).submit("标题", "ISSUE", "<p>正文</p>", principal);
        verify(service).list(null, principal);
        verify(service).get(7L, principal);
        verify(service).reply(7L, "<p>补充</p>", principal);
    }
}

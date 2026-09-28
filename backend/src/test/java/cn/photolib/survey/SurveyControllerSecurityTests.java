package cn.photolib.survey;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 三个问卷权限在接口层各守一段：新建（SURVEY_CREATE）、访问填写（SURVEY_ACCESS）、
 * 看结果（SURVEY_RESULT_VIEW）。持有其中一个不能顺带打开另外两段。
 */
@SpringBootTest
class SurveyControllerSecurityTests {
    @Autowired private SurveyController controller;
    @MockitoBean private SurveyService surveys;
    @MockitoBean private SurveyResponseService responses;

    private final AuthenticatedUser principal = new AuthenticatedUser(
            1L, "survey-security", "问卷安全测试", UserRole.MINISTER, null, false);

    @Test
    @WithMockUser(authorities = "SURVEY_ACCESS")
    void respondentsCannotManageSurveysOrReadResults() {
        assertThatThrownBy(() -> controller.audience(principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.publish(7L, new SurveyController.VersionRequest(1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.list(1, 20, null, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.responses(7L, 1, 20, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.export(7L, principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.response(7L, principal)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(surveys, responses);

        controller.assigned(principal);
        controller.fill(7L, principal);
        controller.submit(7L, new SurveyController.SubmitRequest(Map.of()), principal);
        verify(responses).assigned(principal);
        verify(responses).fillView(7L, principal);
        verify(responses).submit(7L, Map.of(), principal);
    }

    @Test
    @WithMockUser(authorities = "SURVEY_CREATE")
    void creatorsCannotReadResultsOrFillWithoutTheirOwnPermissions() {
        assertThatThrownBy(() -> controller.responses(7L, 1, 20, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.summary(7L, principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.fill(7L, principal)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(responses);

        controller.audience(principal);
        controller.close(7L, new SurveyController.VersionRequest(2), principal);
        verify(surveys).audienceOptions(principal);
        verify(surveys).close(7L, 2, principal);
    }

    @Test
    @WithMockUser(authorities = "SURVEY_RESULT_VIEW")
    void resultViewersCannotEditOrFill() {
        assertThatThrownBy(() -> controller.close(7L, new SurveyController.VersionRequest(1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(7L, 1, principal)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.assigned(principal)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(surveys);

        controller.list(1, 20, null, null, principal);
        controller.summary(7L, principal);
        verify(surveys).list(1, 20, null, null, principal);
        verify(responses).summary(7L, principal);
    }
}

package cn.photolib.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    @Test
    void accessDenied_shouldReturnForbiddenInsteadOfInternalServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("requestId", "test-request-id");

        var response = new GlobalExceptionHandler().handleAccessDenied(
                new AccessDeniedException("denied"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.FORBIDDEN.name());
        assertThat(response.getBody().requestId()).isEqualTo("test-request-id");
    }
}

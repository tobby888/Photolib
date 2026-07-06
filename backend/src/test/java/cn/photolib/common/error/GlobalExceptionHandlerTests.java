package cn.photolib.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @Test
    void missingStaticResource_shouldReturnNotFoundInsteadOfInternalServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        var response = new GlobalExceptionHandler().handleMissingResource(
                new NoResourceFoundException(HttpMethod.GET, "/favicon.ico", "No static resource"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name());
    }
}

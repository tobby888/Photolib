package cn.photolib.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointUploadLimitFilterTests {
    @Test
    void rejectsOversizedSmallFileEndpointBeforeMultipartParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/branding/icon");
        request.setRequestURI("/api/v1/branding/icon");
        request.setContent(new byte[600 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EndpointUploadLimitFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("FILE_TOO_LARGE");
    }

    @Test
    void rejectsOversizedScheduledIconBatchOnPut() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/branding/scheduled-icons");
        request.setRequestURI("/api/v1/branding/scheduled-icons");
        request.setContent(new byte[12 * 1024 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EndpointUploadLimitFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("FILE_TOO_LARGE");
    }
}

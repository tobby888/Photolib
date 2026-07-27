package cn.photolib.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;

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

    @Test
    void rejectsOversizedAvatarBeforeMultipartParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/users/me/avatar");
        request.setRequestURI("/api/v1/users/me/avatar");
        request.setContent(new byte[1200 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EndpointUploadLimitFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("FILE_TOO_LARGE");
    }

    @Test
    void rejectsStreamedOversizedAvatarWhenContentLengthIsUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/users/me/avatar") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setRequestURI("/api/v1/users/me/avatar");
        request.setContent(new byte[1200 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EndpointUploadLimitFilter().doFilter(request, response, (incoming, outgoing) ->
                incoming.getInputStream().transferTo(OutputStream.nullOutputStream()));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("FILE_TOO_LARGE");
    }
}

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
    @Test
    void rejectsOversizedDatabaseBackupUploadByDeclaredContentLength() throws Exception {
        // 备份文件上限是 512 MiB，用声明的 Content-Length 判断，避免测试真的分配这么大的数组。
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/database-backups/upload") {
            @Override
            public long getContentLengthLong() {
                return 600L * 1024 * 1024;
            }
        };
        request.setRequestURI("/api/v1/database-backups/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new EndpointUploadLimitFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("FILE_TOO_LARGE");
    }

    @Test
    void letsANormalSizedDatabaseBackupUploadThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/database-backups/upload");
        request.setRequestURI("/api/v1/database-backups/upload");
        request.setContent(new byte[64 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new EndpointUploadLimitFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}

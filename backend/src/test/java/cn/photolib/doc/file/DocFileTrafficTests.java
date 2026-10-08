package cn.photolib.doc.file;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.doc.DocReader;
import cn.photolib.uploadlimit.TestUploadLimits;
import cn.photolib.uploadlimit.UploadLimit;
import cn.photolib.uploadlimit.UploadLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 文件库的进程内闸门：QPS 与每位读者每小时的次数，数字全部来自上传限额。 */
class DocFileTrafficTests {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-08T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void globalQpsComesFromTheUploadLimitsAndSeparatesUploadsFromDownloads() {
        UploadLimitService limits = TestUploadLimits.with(Map.of(
                UploadLimit.FILE_UPLOAD_QPS, 2L, UploadLimit.FILE_DOWNLOAD_QPS, 1L));
        DocFileTraffic traffic = new DocFileTraffic(limits, mock(JdbcClient.class), FIXED);

        assertThat(traffic.tryAcquireUpload()).isTrue();
        assertThat(traffic.tryAcquireUpload()).isTrue();
        assertThat(traffic.tryAcquireUpload()).isFalse();
        // 上传占满了不影响下载，两个计数各管各的。
        assertThat(traffic.tryAcquireDownload()).isTrue();
        assertThat(traffic.tryAcquireDownload()).isFalse();
    }

    @Test
    void readersAreCountedByAccountOrByPublicAddress() {
        UploadLimitService limits = TestUploadLimits.with(UploadLimit.FILE_DOWNLOADS_PER_HOUR, 1L);
        DocFileTraffic traffic = new DocFileTraffic(limits, mock(JdbcClient.class), FIXED);

        traffic.requireReaderAllowance(new DocReader(1L, null, Set.of()), "203.0.113.1");
        // 同一个账号换了地址还是同一个人。
        assertThatThrownBy(() -> traffic.requireReaderAllowance(new DocReader(1L, null, Set.of()), "203.0.113.2"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RATE_LIMITED);

        traffic.requireReaderAllowance(DocReader.ANONYMOUS, "203.0.113.3");
        assertThatThrownBy(() -> traffic.requireReaderAllowance(DocReader.ANONYMOUS, "203.0.113.3"))
                .isInstanceOf(BusinessException.class);
        // 反向代理后面拿到的是内网地址：失败开放，由匿名每日总流量兜底，不能让全站访客共用一个计数。
        traffic.requireReaderAllowance(DocReader.ANONYMOUS, "10.0.0.8");
        traffic.requireReaderAllowance(DocReader.ANONYMOUS, "10.0.0.8");
    }

    @Test
    void theQpsFilterRejectsBeforeTheBodyIsReadAndOnlyOnFileEndpoints() throws Exception {
        UploadLimitService limits = TestUploadLimits.with(Map.of(
                UploadLimit.FILE_UPLOAD_QPS, 1L, UploadLimit.FILE_DOWNLOAD_QPS, 1L));
        DocFileQpsFilter filter = new DocFileQpsFilter(new DocFileTraffic(limits, mock(JdbcClient.class), FIXED));

        assertThat(run(filter, "POST", "/api/v1/doc-files").getStatus()).isEqualTo(200);
        MockHttpServletResponse rejected = run(filter, "POST", "/api/v1/doc-files");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("RATE_LIMITED");

        assertThat(run(filter, "POST", "/api/v1/public/doc-files/X/download").getStatus()).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/v1/public/doc-files/X/download").getStatus()).isEqualTo(429);
        // 列表、改名、删除不受这两条 QPS 约束。
        assertThat(run(filter, "GET", "/api/v1/public/doc-files").getStatus()).isEqualTo(200);
        assertThat(run(filter, "PUT", "/api/v1/doc-files/7").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse run(DocFileQpsFilter filter, String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

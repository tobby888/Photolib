package cn.photolib.dashboard;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsTests {
    /** 从一个整分钟开始，免得用例里的秒数跨过分钟边界让断言变得难读。 */
    private static final long T0 = 1_800_000_000_000L - (1_800_000_000_000L % 60_000);

    private final AtomicLong now = new AtomicLong(T0);
    private final RequestMetrics metrics = new RequestMetrics(now::get);

    @Test
    void realtimeQpsAveragesTheLastTenCompleteSeconds() {
        for (int second = 0; second < 10; second++) {
            now.set(T0 + second * 1000L);
            for (int i = 0; i < 3; i++) metrics.record("GET /api/v1/photos", 200, 20, 0, 100);
        }
        // 正在进行的这一秒不算：它只填了一部分，算进去会把 QPS 拉低。
        now.set(T0 + 10_000);
        metrics.record("GET /api/v1/photos", 200, 20, 0, 100);

        RequestMetrics.Snapshot snapshot = metrics.snapshot(5);
        assertThat(snapshot.qps()).isEqualTo(3.0);
        assertThat(snapshot.lastMinute().requests()).isEqualTo(30);
        assertThat(snapshot.lastHour().requests()).isEqualTo(31);
        assertThat(snapshot.sinceStart().requests()).isEqualTo(31);
        assertThat(snapshot.sinceStart().bytesOut()).isEqualTo(3100);
    }

    @Test
    void peakQpsIsTheBusiestSingleSecondOfTheLastHour() {
        now.set(T0 + 5_000);
        for (int i = 0; i < 7; i++) metrics.record(null, 200, 1, 0, 0);
        now.set(T0 + 20 * 60_000L);
        for (int i = 0; i < 2; i++) metrics.record(null, 200, 1, 0, 0);

        assertThat(metrics.snapshot(0).peakQpsLastHour()).isEqualTo(7);

        // 一个多小时以后，那一秒已经不在窗口里了。
        now.set(T0 + 61 * 60_000L);
        assertThat(metrics.snapshot(0).peakQpsLastHour()).isEqualTo(2);
    }

    @Test
    void statusCodesLatencyAndBytesAreSplitPerWindow() {
        metrics.record("GET /api/v1/a", 200, 10, 0, 50);
        metrics.record("GET /api/v1/a", 304, 30, 0, 0);
        metrics.record("POST /api/v1/b", 401, 5, 120, 80);
        metrics.record("POST /api/v1/b", 503, 155, 60, 20);

        RequestMetrics.Window hour = metrics.snapshot(0).lastHour();
        assertThat(hour.requests()).isEqualTo(4);
        assertThat(hour.successes()).isEqualTo(1);
        assertThat(hour.redirects()).isEqualTo(1);
        assertThat(hour.clientErrors()).isEqualTo(1);
        assertThat(hour.serverErrors()).isEqualTo(1);
        assertThat(hour.bytesIn()).isEqualTo(180);
        assertThat(hour.bytesOut()).isEqualTo(150);
        assertThat(hour.avgLatencyMs()).isEqualTo(50.0);
        assertThat(hour.maxLatencyMs()).isEqualTo(155);
    }

    /** 环形桶复用格子：过了一整圈以后读到的必须是 0，不能是上一圈留下的数。 */
    @Test
    void staleRingSlotsReadAsEmpty() {
        metrics.record(null, 200, 1, 0, 10);
        now.set(T0 + RequestMetrics.MINUTE_SLOTS * 60_000L + 1_000);

        RequestMetrics.Snapshot snapshot = metrics.snapshot(0);
        assertThat(snapshot.last24Hours().requests()).isZero();
        assertThat(snapshot.lastMinute().requests()).isZero();
        assertThat(snapshot.series().get("24h")).hasSize(96).allMatch(point -> point.requests() == 0);
        // 累计值不受窗口影响。
        assertThat(snapshot.sinceStart().requests()).isEqualTo(1);
    }

    @Test
    void seriesHaveFixedLengthsAndEndWithTheCurrentBucket() {
        now.set(T0 + 30_500);
        metrics.record(null, 200, 1, 0, 0);

        RequestMetrics.Snapshot snapshot = metrics.snapshot(0);
        assertThat(snapshot.series().get("5m")).hasSize(60);
        assertThat(snapshot.series().get("1h")).hasSize(60);
        RequestMetrics.Point last = snapshot.series().get("5m").getLast();
        assertThat(last.requests()).isEqualTo(1);
        assertThat(last.qps()).isEqualTo(0.2);
        assertThat(snapshot.series().get("1h").getLast().requests()).isEqualTo(1);
    }

    @Test
    void topEndpointsAreRankedByVolumeAndCardinalityIsCapped() {
        for (int i = 0; i < 3; i++) metrics.record("GET /api/v1/hot", 200, 10, 0, 0);
        metrics.record("GET /api/v1/hot", 500, 40, 0, 0);
        metrics.record("GET /api/v1/cold", 200, 10, 0, 0);

        var top = metrics.snapshot(2).topEndpoints();
        assertThat(top).hasSize(2);
        assertThat(top.getFirst().endpoint()).isEqualTo("GET /api/v1/hot");
        assertThat(top.getFirst().requests()).isEqualTo(4);
        assertThat(top.getFirst().serverErrors()).isEqualTo(1);
        assertThat(top.getFirst().avgLatencyMs()).isEqualTo(17.5);

        for (int i = 0; i < RequestMetrics.MAX_ENDPOINTS + 5; i++) {
            metrics.record("GET /api/v1/generated/" + i, 200, 1, 0, 0);
        }
        assertThat(metrics.snapshot(1000).topEndpoints())
                .hasSizeLessThanOrEqualTo(RequestMetrics.MAX_ENDPOINTS + 1)
                .anyMatch(endpoint -> endpoint.endpoint().equals(RequestMetrics.OTHER_ENDPOINTS));
    }

    @Test
    void activeUsersAreCountedByLastSeenAndForgottenAfterADay() {
        metrics.markActive(1L);
        now.set(T0 + 10 * 60_000L);
        metrics.markActive(2L);
        metrics.markActive(2L);
        metrics.markActive(null);

        assertThat(metrics.activeUsers()).isEqualTo(new RequestMetrics.ActiveUsers(1, 2, 2));

        now.set(T0 + 25 * 3600_000L);
        assertThat(metrics.activeUsers()).isEqualTo(new RequestMetrics.ActiveUsers(0, 0, 0));
    }

    @Test
    void filterRecordsStatusRouteTemplateAndResponseBytes() throws Exception {
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/photos/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/photos/{photoId}");
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404);
            res.getWriter().write("找不到😀");
            res.getWriter().flush();
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("找不到😀");
        RequestMetrics.Snapshot snapshot = metrics.snapshot(5);
        // 「近 1 分钟」不含正在进行的这一秒，用近 1 小时的窗口看刚记下的请求。
        assertThat(snapshot.lastHour().clientErrors()).isEqualTo(1);
        // 三个汉字各 3 字节，表情是代理对，UTF-8 下 4 字节。
        assertThat(snapshot.lastHour().bytesOut()).isEqualTo(13);
        assertThat(snapshot.topEndpoints()).singleElement()
                .extracting(RequestMetrics.Endpoint::endpoint).isEqualTo("GET /api/v1/photos/{photoId}");
    }

    @Test
    void filterCountsStreamedBytesAndSkipsNonApiRoutesInTheEndpointRanking() throws Exception {
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/index.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> res.getOutputStream().write(new byte[2048], 0, 1024));

        RequestMetrics.Snapshot snapshot = metrics.snapshot(5);
        assertThat(snapshot.lastHour().requests()).isEqualTo(1);
        assertThat(snapshot.lastHour().bytesOut()).isEqualTo(1024);
        assertThat(snapshot.topEndpoints()).isEmpty();
    }
}

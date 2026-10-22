package cn.photolib.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTests {
    private static MockHttpServletRequest from(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void behindLocalNginxUsesTheRealIpHeader() {
        MockHttpServletRequest request = from("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7");

        assertThat(ClientIp.of(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void withoutRealIpTakesTheHopNextToTheProxyNotTheClientSuppliedOne() {
        // nginx 的 $proxy_add_x_forwarded_for 是追加：最左边是客户端自己填的，最右边才是 nginx 看到的。
        MockHttpServletRequest request = from("0:0:0:0:0:0:0:1");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 2001:db8::5");

        assertThat(ClientIp.of(request)).isEqualTo("2001:db8::5");
    }

    @Test
    void directRequestsCannotSpoofTheAddress() {
        MockHttpServletRequest request = from("198.51.100.9");
        request.addHeader("X-Real-IP", "6.6.6.6");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertThat(ClientIp.of(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void ignoresGarbageHeadersAndFallsBackToRemoteAddr() {
        MockHttpServletRequest request = from("127.0.0.1");
        request.addHeader("X-Real-IP", "<script>alert(1)</script>");
        request.addHeader("X-Forwarded-For", "not an ip");

        assertThat(ClientIp.of(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void localRequestsWithoutHeadersKeepLoopback() {
        assertThat(ClientIp.of(from("127.0.0.1"))).isEqualTo("127.0.0.1");
    }
}

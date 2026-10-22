package cn.photolib.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 限速用的客户端地址：只信本机 nginx 覆盖写入的 X-Real-IP，IPv6 按 /64 归并，额度按出口人数放大。 */
class RateLimitAddressTests {
    private static MockHttpServletRequest from(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void behindLocalNginxCountsTheVisitorNotTheProxy() {
        MockHttpServletRequest request = from("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.7");

        assertThat(ClientAddress.normalize(RateLimitAddress.of(request))).isEqualTo("203.0.113.7");
    }

    @Test
    void neverReadsTheAppendedForwardedForChain() {
        // X-Forwarded-For 最左边是客户端自己写的：信了它，换一个值就换一份额度。
        MockHttpServletRequest request = from("127.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertThat(RateLimitAddress.of(request)).isEqualTo("127.0.0.1");
        assertThat(ClientAddress.normalize(RateLimitAddress.of(request))).isNull();
    }

    @Test
    void directRequestsCannotPickTheirOwnKey() {
        MockHttpServletRequest request = from("198.51.100.9");
        request.addHeader("X-Real-IP", "6.6.6.6");

        assertThat(RateLimitAddress.of(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void malformedHeaderFallsBackToTheConnection() {
        MockHttpServletRequest request = from("127.0.0.1");
        request.addHeader("X-Real-IP", "evil.example");

        assertThat(RateLimitAddress.of(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6AddressesInOneSlash64ShareAKey() {
        assertThat(ClientAddress.normalize("2001:db8:1:2::5"))
                .isEqualTo(ClientAddress.normalize("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"))
                .isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientAddress.normalize("2001:db8:1:3::5")).isEqualTo("2001:db8:1:3::/64");
        assertThat(ClientAddress.normalize("2001:db8::")).isEqualTo("2001:db8:0:0::/64");
        assertThat(ClientAddress.normalize("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        for (String invalid : new String[]{"2001:db8:::1", "1:2:3:4:5:6:7:8:9", "abcd", "2001:db8::1::2", "12345::1"}) {
            assertThat(ClientAddress.normalize(invalid)).as(invalid).isNull();
        }
    }

    @Test
    void scalingNeverOverflowsOrDropsBelowOneVisitor() {
        assertThat(SharedAddressAllowance.fixed(1000).perAddress(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(SharedAddressAllowance.fixed(0).perAddress(8)).isEqualTo(8);
    }
}

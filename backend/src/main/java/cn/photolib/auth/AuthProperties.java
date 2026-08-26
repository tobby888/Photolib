package cn.photolib.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "photolib.auth")
public record AuthProperties(
        Duration accessTtl,
        Duration idleTtl,
        boolean secureCookie,
        LoginThrottleProperties loginThrottle
) {
    public AuthProperties {
        loginThrottle = loginThrottle == null
                ? LoginThrottleProperties.defaults() : loginThrottle.withDefaults();
    }

    /**
     * Login throttling limits. Defaults are filled in here rather than only in
     * {@code application.yml} so that a deployment which never heard of these keys
     * still gets the protection — an unconfigured throttle would be no throttle.
     *
     * @param maxIdentifierFailures failures against one account before it is locked
     * @param maxAddressFailures    failures from one client address before it is locked;
     *                              set well above the per-account limit so that shared
     *                              campus NAT addresses are not locked by ordinary typos
     * @param failureWindow         how long failures accumulate before the count restarts
     * @param lockDuration          how long a locked account or address stays locked
     */
    public record LoginThrottleProperties(
            Integer maxIdentifierFailures,
            Integer maxAddressFailures,
            Duration failureWindow,
            Duration lockDuration
    ) {
        static LoginThrottleProperties defaults() {
            return new LoginThrottleProperties(null, null, null, null).withDefaults();
        }

        LoginThrottleProperties withDefaults() {
            return new LoginThrottleProperties(
                    maxIdentifierFailures == null || maxIdentifierFailures < 1 ? 5 : maxIdentifierFailures,
                    maxAddressFailures == null || maxAddressFailures < 1 ? 40 : maxAddressFailures,
                    failureWindow == null || failureWindow.isZero() || failureWindow.isNegative()
                            ? Duration.ofMinutes(15) : failureWindow,
                    lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()
                            ? Duration.ofMinutes(15) : lockDuration);
        }
    }
}

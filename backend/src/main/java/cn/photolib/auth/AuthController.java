package cn.photolib.auth;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.mfa.MfaService;
import cn.photolib.auth.mfa.WebAuthnSupport;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.api.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final String REFRESH_COOKIE = "photolib_refresh";
    /**
     * 信任浏览器的 Cookie，名字里带账号 id：同一台电脑上登过几个账号，各自的信任互不覆盖。
     * 路径只到登录接口，其余请求根本不会带上它。
     */
    private static final String TRUSTED_COOKIE_PREFIX = "photolib_mfa_trust_";
    private static final String TRUSTED_COOKIE_PATH = "/api/v1/auth/login";
    private static final int MAX_TRUSTED_LABEL_LENGTH = 255;
    private static final int MAX_AUDITED_IDENTIFIER_LENGTH = 190;
    private final AuthService authService;
    private final AuthProperties properties;
    private final LoginThrottle loginThrottle;
    private final MfaService mfaService;
    private final WebAuthnSupport webAuthn;

    /**
     * Throttling lives here rather than inside {@link AuthService#login} because a
     * failed login unwinds that method's transaction; a counter written inside it
     * would roll back with the failure and never accumulate.
     */
    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest servletRequest,
                                     HttpServletResponse response) {
        String identifier = request.username();
        String remoteAddress = servletRequest.getRemoteAddr();
        // The audit interceptor records this request's outcome; give it the account
        // that was targeted, so a guessing run is legible afterwards. Never the password.
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE,
                Map.of("identifier", auditIdentifier(identifier)));
        loginThrottle.requireNotLocked(identifier, remoteAddress);
        AuthService.LoginOutcome outcome;
        try {
            outcome = authService.login(identifier, request.password(),
                    userId -> cookieValue(servletRequest, TRUSTED_COOKIE_PREFIX + userId));
        } catch (RuntimeException failure) {
            loginThrottle.recordFailure(identifier, remoteAddress);
            throw failure;
        }
        // 密码是对的，账号计数就清掉；第二步的验证码另有按账号的计数，不受这里影响。
        loginThrottle.clearIdentifier(identifier);
        if (outcome.challenge() != null) {
            MfaService.LoginChallenge challenge = outcome.challenge();
            return ApiResponse.ok(LoginResponse.mfaPending(challenge));
        }
        if (outcome.trustedToken() != null) {
            setTrustedCookie(response, outcome.pair().user().id(), outcome.trustedToken(),
                    mfaService.trustedBrowserTtl());
        }
        setRefreshCookie(response, outcome.pair().refreshToken());
        return ApiResponse.ok(toResponse(outcome.pair()));
    }

    /**
     * 登录第二步。票据来自上一步的密码登录，本身就是凭据，所以这条接口匿名可调。
     *
     * <p>和密码登录一样，失败计数放在事务外：校验失败会回滚 {@code completeMfaLogin}
     * 的事务，写在里面的计数会被一起撤销。
     */
    @PostMapping("/login/mfa")
    ApiResponse<LoginResponse> verifyLogin(@RequestBody MfaLoginRequest request,
                                           HttpServletRequest servletRequest,
                                           HttpServletResponse response) {
        Long userId = mfaService.loginTicketUser(request.ticket());
        servletRequest.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("userId", userId));
        loginThrottle.requireMfaNotLocked(userId, LoginThrottle.MfaAttempt.LOGIN);
        AuthService.TokenPair pair;
        try {
            pair = authService.completeMfaLogin(request.ticket(), request.verification(),
                    webAuthn.relyingParty(servletRequest));
        } catch (BusinessException failure) {
            if (failure.getCode() == ErrorCode.MFA_INVALID_CODE) {
                // 走到第二步说明密码是对的。连错到被锁，多半是密码已经落到别人手里。
                if (loginThrottle.recordMfaFailure(userId, LoginThrottle.MfaAttempt.LOGIN)) {
                    mfaService.warnSecondStepLocked(userId);
                }
            }
            throw failure;
        }
        loginThrottle.clearMfa(userId, LoginThrottle.MfaAttempt.LOGIN);
        if (Boolean.TRUE.equals(request.trustDevice())) {
            String token = mfaService.trustBrowser(userId, browserLabel(servletRequest));
            setTrustedCookie(response, userId, token, mfaService.trustedBrowserTtl());
        }
        setRefreshCookie(response, pair.refreshToken());
        return ApiResponse.ok(toResponse(pair));
    }

    @PostMapping("/login/mfa/webauthn-options")
    ApiResponse<Map<String, Object>> loginWebAuthnOptions(@RequestBody MfaTicketRequest request,
                                                          HttpServletRequest servletRequest) {
        return ApiResponse.ok(mfaService.loginWebAuthnOptions(request.ticket(),
                webAuthn.relyingParty(servletRequest)));
    }

    private static String auditIdentifier(String identifier) {
        if (identifier == null) return "";
        String trimmed = identifier.trim();
        return trimmed.length() > MAX_AUDITED_IDENTIFIER_LENGTH
                ? trimmed.substring(0, MAX_AUDITED_IDENTIFIER_LENGTH) : trimmed;
    }

    @PostMapping("/refresh")
    ApiResponse<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        AuthService.TokenPair pair = authService.refresh(refreshToken);
        setRefreshCookie(response, pair.refreshToken());
        return ApiResponse.ok(toResponse(pair));
    }

    @GetMapping("/me")
    ApiResponse<AuthenticatedUser> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(user);
    }

    @PutMapping("/initial-password")
    ApiResponse<LoginResponse> initialPassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody InitialPasswordRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        Long sessionId = servletRequest.getAttribute(AccessTokenFilter.SESSION_ID_ATTRIBUTE) instanceof Long id
                ? id : null;
        AuthService.TokenPair pair = authService.changeInitialPassword(
                user, sessionId, request.initialPassword(), request.newPassword());
        setRefreshCookie(response, pair.refreshToken());
        return ApiResponse.ok(toResponse(pair));
    }

    @PutMapping("/password")
    ApiResponse<Void> password(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PasswordRequest request,
            HttpServletResponse response) {
        authService.changePassword(user, request.oldPassword(), request.newPassword());
        clearRefreshCookie(response);
        return ApiResponse.ok();
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        clearRefreshCookie(response);
        return ApiResponse.ok();
    }

    private LoginResponse toResponse(AuthService.TokenPair pair) {
        return new LoginResponse(pair.accessToken(), "Bearer", pair.expiresIn(),
                pair.user().mustChangePassword(), pair.user(), false, null, null, 0);
    }

    private void setTrustedCookie(HttpServletResponse response, Long userId, String value, Duration ttl) {
        ResponseCookie cookie = ResponseCookie.from(TRUSTED_COOKIE_PREFIX + userId, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(TRUSTED_COOKIE_PATH)
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    /** 信任列表里给人看的浏览器描述，只取 User-Agent，不做任何指纹采集。 */
    private static String browserLabel(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent == null || agent.isBlank()) return null;
        return agent.length() > MAX_TRUSTED_LABEL_LENGTH ? agent.substring(0, MAX_TRUSTED_LABEL_LENGTH) : agent;
    }

    private void setRefreshCookie(HttpServletResponse response, String value) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(properties.idleTtl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.secureCookie());
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    record LoginRequest(@NotBlank @Size(max = 320) String username,
                        @NotBlank @Size(max = 72) String password) {
    }

    /**
     * 登录应答。{@code mfaRequired} 为真时还没有会话：只有 {@code mfaTicket} 和可用的验证方式，
     * 前端拿票据去 {@code /auth/login/mfa} 完成第二步。
     */
    record LoginResponse(String accessToken, String tokenType, long expiresIn,
                         boolean mustChangePassword, AuthenticatedUser user,
                         boolean mfaRequired, String mfaTicket, List<String> mfaMethods, long mfaExpiresIn) {
        static LoginResponse mfaPending(MfaService.LoginChallenge challenge) {
            return new LoginResponse(null, null, 0, false, null, true, challenge.ticket(),
                    challenge.methods(), challenge.expiresIn());
        }
    }

    record MfaTicketRequest(@NotBlank String ticket) {
    }

    record MfaLoginRequest(String ticket, String code, WebAuthnSupport.Assertion assertion, Boolean trustDevice) {
        MfaService.Verification verification() {
            return new MfaService.Verification(code, assertion, null);
        }
    }

    record InitialPasswordRequest(@NotBlank String initialPassword,
                                  @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{10,72}$",
                                          message = "密码至少10位，且必须包含字母和数字")
                                  String newPassword) {
    }

    record PasswordRequest(@NotBlank String oldPassword,
                           @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{10,72}$",
                                   message = "密码至少10位，且必须包含字母和数字")
                           String newPassword) {
    }
}

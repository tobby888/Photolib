package cn.photolib.auth;

import cn.photolib.audit.AuditInterceptor;
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

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final String REFRESH_COOKIE = "photolib_refresh";
    private static final int MAX_AUDITED_IDENTIFIER_LENGTH = 190;
    private final AuthService authService;
    private final AuthProperties properties;
    private final LoginThrottle loginThrottle;

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
        AuthService.TokenPair pair;
        try {
            pair = authService.login(identifier, request.password());
        } catch (RuntimeException failure) {
            loginThrottle.recordFailure(identifier, remoteAddress);
            throw failure;
        }
        loginThrottle.clearIdentifier(identifier);
        setRefreshCookie(response, pair.refreshToken());
        return ApiResponse.ok(toResponse(pair));
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
            HttpServletResponse response) {
        AuthService.TokenPair pair = authService.changeInitialPassword(
                user, request.initialPassword(), request.newPassword());
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
                pair.user().mustChangePassword(), pair.user());
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

    record LoginResponse(String accessToken, String tokenType, long expiresIn,
                         boolean mustChangePassword, AuthenticatedUser user) {
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

package cn.photolib.auth;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/deplyt")
public class DeplytAuthorizationController {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new HashMap<>();
    private final String callback;
    private final byte[] secretHash;

    public DeplytAuthorizationController(
            @Value("${DEPLYT_PUBLIC_URL:}") String origin,
            @Value("${DEPLYT_SSO_SECRET:}") String secret) {
        if (origin.isEmpty() && secret.isEmpty()) {
            callback = null;
            secretHash = null;
            return;
        }
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches("/?") || secret.length() < 32) {
            throw new IllegalArgumentException("Deplyt SSO requires an HTTPS origin and a 32-character secret");
        }
        callback = "https://" + uri.getRawAuthority() + "/auth/callback";
        secretHash = sha256(secret);
    }

    @PostMapping("/authorize")
    @PreAuthorize("hasRole('ADMIN')")
    public synchronized ApiResponse<Authorization> authorize(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @RequestBody AuthorizationRequest request) {
        requireEnabled();
        if (user == null || !user.isAdministrator() || user.mustChangePassword() || user.mfa().enrollmentRequired()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅 PhotoLib 管理员可授权 Deplyt");
        }
        if (!isToken(request.challenge()) || !isToken(request.state())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无效的 Deplyt 登录请求");
        }
        Instant now = Instant.now();
        grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt()));
        if (grants.size() >= 512) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "请稍后重试 Deplyt 登录");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = BASE64.encodeToString(bytes);
        grants.put(code, new Grant(request.challenge(), new Identity(user.id().toString(), user.username()),
                now.plusSeconds(60)));
        return ApiResponse.ok(new Authorization(callback + "?code=" + code + "&state=" + request.state()));
    }

    @PostMapping("/token")
    public synchronized ApiResponse<Identity> exchange(
            @RequestHeader(value = "X-Deplyt-Client-Secret", defaultValue = "") String secret,
            @RequestBody ExchangeRequest request) {
        requireEnabled();
        if (!MessageDigest.isEqual(secretHash, sha256(secret)) || !isToken(request.code())
                || !isToken(request.verifier())) {
            throw invalidGrant();
        }
        Grant grant = grants.get(request.code());
        if (grant == null || !Instant.now().isBefore(grant.expiresAt())
                || !grant.challenge().equals(BASE64.encodeToString(sha256(request.verifier())))) {
            throw invalidGrant();
        }
        grants.remove(request.code());
        return ApiResponse.ok(grant.identity());
    }

    private void requireEnabled() {
        if (callback == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "PhotoLib 未启用 Deplyt 登录");
        }
    }

    private static BusinessException invalidGrant() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "无效或已过期的 Deplyt 授权");
    }

    private static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record AuthorizationRequest(String state, String challenge) {}
    record Authorization(String callbackUrl) {}
    record ExchangeRequest(String code, String verifier) {}
    record Identity(String id, String username) {}
    private record Grant(String challenge, Identity identity, Instant expiresAt) {}
}

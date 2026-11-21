package cn.photolib.auth;

import cn.photolib.audit.AuditAction;
import cn.photolib.audit.AuditSkip;
import cn.photolib.auth.mfa.RequiresStepUp;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.user.UserService;
import cn.photolib.user.model.UserEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/idp")
public class IdpAuthController {
    private static final String STATE_COOKIE = "photolib_idp_state";
    private static final String REFRESH_COOKIE = "photolib_refresh";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    private final AuthService authService;
    private final AuthProperties authProperties;
    private final UserService userService;
    private final String publicUrl;
    private final String internalUrl;
    private final String clientId;
    private final String clientSecret;
    private HttpClient httpClient;

    @Autowired
    public IdpAuthController(
            AuthService authService,
            AuthProperties authProperties,
            UserService userService,
            @Value("${IDP_PUBLIC_URL:}") String publicUrl,
            @Value("${IDP_INTERNAL_URL:}") String internalUrl,
            @Value("${IDP_CLIENT_ID:photolib}") String clientId,
            @Value("${IDP_CLIENT_SECRET:}") String clientSecret) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.userService = userService;
        this.publicUrl = normalizeOrigin(DeplytAuthorizationController.stripQuotes(publicUrl));
        String cleanedInternal = normalizeOrigin(DeplytAuthorizationController.stripQuotes(internalUrl));
        this.internalUrl = cleanedInternal.isEmpty() ? this.publicUrl : cleanedInternal;
        this.clientId = DeplytAuthorizationController.stripQuotes(clientId).isEmpty()
                ? "photolib" : DeplytAuthorizationController.stripQuotes(clientId);
        this.clientSecret = DeplytAuthorizationController.stripQuotes(clientSecret);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public boolean isEnabled() {
        return !publicUrl.isEmpty() && clientSecret.length() >= 32;
    }

    @GetMapping("/config")
    public ApiResponse<IdpConfigView> config() {
        boolean enabled = isEnabled();
        String base = enabled ? publicUrl : "";
        return ApiResponse.ok(new IdpConfigView(
                enabled,
                base,
                enabled ? base + "/#register" : "",
                enabled ? base + "/#users" : "",
                enabled ? base + "/#codes" : "",
                enabled ? base + "/#reviews" : "",
                clientId));
    }

    @PostMapping("/start")
    @AuditSkip("只签发一次性 PKCE 状态 Cookie，登录结果在兑换令牌时记录")
    public ApiResponse<StartResponse> start(@RequestBody(required = false) StartRequest request,
                                            HttpServletRequest servletRequest,
                                            HttpServletResponse response) {
        requireEnabled();
        String redirectUri = resolveRedirectUri(request == null ? null : request.redirectUri(), servletRequest);
        String state = randomToken();
        String verifier = randomToken();
        String challenge = BASE64.encodeToString(sha256(verifier));

        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state + "." + verifier)
                .httpOnly(true)
                .secure(authProperties.secureCookie())
                .sameSite("Lax")
                .path("/api/v1/auth/idp")
                .maxAge(Duration.ofMinutes(10))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        String authorizeUrl = publicUrl + "/oauth/authorize?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256";
        return ApiResponse.ok(new StartResponse(authorizeUrl));
    }

    @PostMapping("/exchange")
    @AuditAction("通过统一认证中心登录")
    public ApiResponse<AuthController.LoginResponse> exchange(
            @CookieValue(name = STATE_COOKIE, required = false) String stateCookie,
            @RequestBody ExchangeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        requireEnabled();
        clearStateCookie(response);
        if (stateCookie == null || request == null || !isToken(request.code()) || !isToken(request.state())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无效或已过期的单点登录状态，请重新发起登录");
        }
        String[] parts = stateCookie.split("\\.", 2);
        if (parts.length != 2 || !isToken(parts[1])
                || !MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.UTF_8),
                request.state().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "单点登录状态校验未通过，请重新发起登录");
        }
        String verifier = parts[1];
        String redirectUri = resolveRedirectUri(request.redirectUri(), servletRequest);

        UserService.IdpUserProfile profile = exchangeWithIdp(request.code(), verifier, redirectUri);
        if (!profile.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号在统一认证中心已被停用或未获准访问 PhotoLib");
        }
        UserEntity user = userService.syncFromIdp(profile);
        AuthService.TokenPair pair = authService.issueForIdpUser(user.getId());
        setRefreshCookie(response, pair.refreshToken());
        return ApiResponse.ok(new AuthController.LoginResponse(
                pair.accessToken(), "Bearer", pair.expiresIn(),
                pair.user().mustChangePassword(), pair.user(),
                false, null, List.of(), 0));
    }

    @PostMapping("/sync")
    @AuditAction("同步统一认证中心账号")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresStepUp
    public ApiResponse<Map<String, Integer>> syncUsers() {
        requireEnabled();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(internalUrl + "/api/v1/oauth/users"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("X-Idp-Client-Id", clientId)
                    .header("X-Idp-Client-Secret", clientSecret)
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "无法从统一认证中心拉取账号列表（HTTP " + res.statusCode() + "）");
            }
            JsonNode root = json.readTree(res.body());
            if (!"OK".equals(root.path("code").asText()) || !root.path("data").isArray()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "统一认证中心返回的数据格式无效");
            }
            int count = 0;
            for (JsonNode item : root.path("data")) {
                UserService.IdpUserProfile profile = parseUserProfile(item);
                if (StringUtils.hasText(profile.username())) {
                    userService.syncFromIdp(profile);
                    count++;
                }
            }
            return ApiResponse.ok(Map.of("synced", count));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无法连接统一认证中心同步账号");
        }
    }

    private UserService.IdpUserProfile exchangeWithIdp(String code, String verifier, String redirectUri) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "clientId", clientId,
                    "clientSecret", clientSecret,
                    "code", code,
                    "verifier", verifier,
                    "code_verifier", verifier,
                    "redirectUri", redirectUri));
            HttpRequest req = HttpRequest.newBuilder(URI.create(internalUrl + "/api/v1/oauth/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Idp-Client-Id", clientId)
                    .header("X-Idp-Client-Secret", clientSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "统一认证中心拒绝了授权码兑换");
            }
            JsonNode root = json.readTree(res.body());
            if (!"OK".equals(root.path("code").asText())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "统一认证中心未确认此次登录");
            }
            JsonNode data = root.path("data");
            JsonNode userNode = data.has("user") && data.path("user").isObject() ? data.path("user") : data;
            UserService.IdpUserProfile profile = parseUserProfile(userNode);
            if (!StringUtils.hasText(profile.sub()) || !StringUtils.hasText(profile.username())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "统一认证中心返回的账号身份不完整");
            }
            return profile;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "暂时无法连接统一认证中心验证登录");
        }
    }

    private static UserService.IdpUserProfile parseUserProfile(JsonNode node) {
        String sub = node.path("sub").asText("");
        if (sub.isEmpty()) sub = node.path("id").asText("");
        String username = node.path("username").asText("");
        String displayName = node.path("displayName").asText(username);
        String email = node.path("email").asText(null);
        String phone = node.path("phone").asText(null);
        String wecom = node.path("wecomUserid").asText(null);
        boolean admin = node.path("admin").asBoolean(false);
        boolean enabled = !node.has("enabled") || node.path("enabled").asBoolean(true);
        return new UserService.IdpUserProfile(sub, username, displayName, email, phone, wecom, admin, enabled);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "PhotoLib 尚未配置统一认证中心 (PhotolibIdp)");
        }
    }

    private String resolveRedirectUri(String candidate, HttpServletRequest request) {
        if (StringUtils.hasText(candidate)) {
            return candidate.trim();
        }
        String scheme = request.getScheme();
        String host = request.getHeader("Host");
        if (!StringUtils.hasText(host)) {
            host = request.getServerName() + (request.getServerPort() > 0 ? ":" + request.getServerPort() : "");
        }
        return scheme + "://" + host + "/auth/callback";
    }

    private void clearStateCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(STATE_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(authProperties.secureCookie());
        cookie.setPath("/api/v1/auth/idp");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private void setRefreshCookie(HttpServletResponse response, String value) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(authProperties.secureCookie())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(authProperties.idleTtl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return BASE64.encodeToString(bytes);
    }

    private static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    private static String normalizeOrigin(String value) {
        if (!StringUtils.hasText(value)) return "";
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record IdpConfigView(boolean enabled, String publicUrl, String registerUrl,
                                String usersUrl, String codesUrl, String reviewsUrl, String clientId) {}
    public record StartRequest(String redirectUri) {}
    public record StartResponse(String authorizeUrl) {}
    public record ExchangeRequest(String code, String state, String redirectUri) {}
}

package cn.photolib.auth.mfa;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.exception.WebAuthnException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 安全密钥 / 通行密钥（WebAuthn）。浏览器那个"用手机扫码或插入 USB 密钥"的系统弹窗
 * 就是它：页面调 {@code navigator.credentials.create/get}，这里生成参数、校验结果。
 *
 * <p>只做"第二因素"：不要求常驻凭据（resident key），也不强制用户验证（PIN / 指纹），
 * 触碰一下密钥即可——密码已经是第一因素了。证明（attestation）选 none，不收集密钥型号。
 *
 * <p>所有二进制字段在接口上一律用 base64url（不带填充），和浏览器的 JSON 序列化习惯一致。
 */
@Component
public class WebAuthnSupport {
    private static final long TIMEOUT_MILLIS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final List<COSEAlgorithmIdentifier> ALGORITHMS = List.of(
            COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256);

    private final MfaProperties properties;
    private final WebAuthnManager manager;
    private final AttestedCredentialDataConverter credentialConverter;

    public WebAuthnSupport(MfaProperties properties) {
        this.properties = properties;
        ObjectConverter converter = new ObjectConverter();
        this.manager = WebAuthnManager.createNonStrictWebAuthnManager(converter);
        this.credentialConverter = new AttestedCredentialDataConverter(converter);
    }

    /** 页面所在站点。WebAuthn 凭据绑定在 RP ID 上，换了域名就用不了。 */
    public record RelyingParty(String id, Set<String> origins) {
    }

    public record RegisteredCredential(String credentialId, byte[] credentialData, long signCount,
                                       String transports) {
    }

    public record StoredCredential(String credentialId, byte[] credentialData, long signCount,
                                   String transports) {
    }

    /** 浏览器 {@code navigator.credentials.get()} 回来的断言，字段都是 base64url。 */
    public record Assertion(String credentialId, String clientDataJSON, String authenticatorData,
                            String signature, String userHandle) {
    }

    /** 浏览器 {@code navigator.credentials.create()} 回来的结果，字段都是 base64url。 */
    public record Attestation(String clientDataJSON, String attestationObject, List<String> transports) {
    }

    /**
     * 配置了 {@code MFA_WEBAUTHN_RP_ID} 就以配置为准；没配时从请求的 Origin 头推断，
     * 只适合本地开发（Vite 代理下后端看到的 Host 和浏览器地址栏不是一个）。
     *
     * <p>推断不会让钓鱼站点得逞：浏览器只允许页面使用自己域名下的 RP ID，仿冒站点拿到的
     * 断言绑定在仿冒域名上，而库里的凭据是在真域名下注册的，对不上。
     */
    public RelyingParty relyingParty(HttpServletRequest request) {
        if (!properties.rpId().isEmpty()) {
            Set<String> origins = properties.origins().isEmpty()
                    ? Set.of("https://" + properties.rpId()) : new LinkedHashSet<>(properties.origins());
            return new RelyingParty(properties.rpId(), origins);
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            String scheme = request.getScheme();
            int port = request.getServerPort();
            boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            origin = scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        String host;
        try {
            host = URI.create(origin).getHost();
        } catch (IllegalArgumentException ex) {
            host = null;
        }
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无法确定站点域名，安全密钥暂不可用");
        }
        return new RelyingParty(host, Set.of(origin));
    }

    public static String newChallenge() {
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        return B64.encodeToString(challenge);
    }

    /** 用户句柄只需在本站唯一、且不含个人信息，用账号 id 的 8 个字节即可。 */
    static String userHandle(Long userId) {
        return B64.encodeToString(ByteBuffer.allocate(8).putLong(userId).array());
    }

    public Map<String, Object> creationOptions(RelyingParty rp, String challenge, Long userId, String username,
                                               String displayName, List<StoredCredential> existing) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", challenge);
        options.put("rp", Map.of("id", rp.id(), "name", properties.rpName()));
        options.put("user", Map.of("id", userHandle(userId), "name", username,
                "displayName", displayName == null || displayName.isBlank() ? username : displayName));
        options.put("pubKeyCredParams", ALGORITHMS.stream()
                .map(alg -> Map.of("type", "public-key", "alg", alg.getValue())).toList());
        options.put("timeout", TIMEOUT_MILLIS);
        // 同一把密钥不要绑两次：浏览器看到 excludeCredentials 里的凭据会直接提示"已注册"。
        options.put("excludeCredentials", descriptors(existing));
        options.put("authenticatorSelection", Map.of(
                "residentKey", "discouraged", "requireResidentKey", false, "userVerification", "discouraged"));
        options.put("attestation", "none");
        return options;
    }

    public Map<String, Object> requestOptions(RelyingParty rp, String challenge, List<StoredCredential> credentials) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", challenge);
        options.put("rpId", rp.id());
        options.put("allowCredentials", descriptors(credentials));
        options.put("timeout", TIMEOUT_MILLIS);
        options.put("userVerification", "discouraged");
        return options;
    }

    public RegisteredCredential verifyRegistration(RelyingParty rp, String challenge, Attestation attestation) {
        try {
            Set<String> transports = attestation.transports() == null ? Set.of()
                    : new LinkedHashSet<>(attestation.transports());
            RegistrationRequest request = new RegistrationRequest(decode(attestation.attestationObject()),
                    decode(attestation.clientDataJSON()), transports);
            RegistrationParameters parameters = new RegistrationParameters(serverProperty(rp, challenge),
                    ALGORITHMS.stream().map(alg -> new PublicKeyCredentialParameters(
                            PublicKeyCredentialType.PUBLIC_KEY, alg)).toList(), false, true);
            RegistrationData data = manager.verify(request, parameters);
            AttestedCredentialData credential = data.getAttestationObject().getAuthenticatorData()
                    .getAttestedCredentialData();
            return new RegisteredCredential(B64.encodeToString(credential.getCredentialId()),
                    credentialConverter.convert(credential),
                    data.getAttestationObject().getAuthenticatorData().getSignCount(),
                    String.join(",", transports));
        } catch (WebAuthnException | IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "安全密钥校验失败，请重试");
        }
    }

    /** 校验断言，返回密钥新的签名计数。 */
    public long verifyAssertion(RelyingParty rp, String challenge, StoredCredential stored, Assertion assertion) {
        try {
            byte[] credentialId = decode(assertion.credentialId());
            AttestedCredentialData credential = credentialConverter.convert(stored.credentialData());
            if (!Arrays.equals(credentialId, credential.getCredentialId())) {
                throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "安全密钥校验失败，请重试");
            }
            CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(), null, null, null,
                    stored.signCount(), credential, null, null, null, transports(stored.transports()));
            AuthenticationRequest request = new AuthenticationRequest(credentialId,
                    assertion.userHandle() == null || assertion.userHandle().isBlank()
                            ? null : decode(assertion.userHandle()),
                    decode(assertion.authenticatorData()), decode(assertion.clientDataJSON()), null,
                    decode(assertion.signature()));
            AuthenticationParameters parameters = new AuthenticationParameters(serverProperty(rp, challenge),
                    record, List.of(credentialId), false, true);
            AuthenticationData data = manager.verify(request, parameters);
            return data.getAuthenticatorData().getSignCount();
        } catch (WebAuthnException | IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "安全密钥校验失败，请重试");
        }
    }

    private ServerProperty serverProperty(RelyingParty rp, String challenge) {
        return new ServerProperty(rp.origins().stream().map(Origin::new).collect(Collectors.toSet()),
                rp.id(), new DefaultChallenge(decode(challenge)));
    }

    private static List<Map<String, Object>> descriptors(List<StoredCredential> credentials) {
        return credentials.stream().map(credential -> {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("type", "public-key");
            descriptor.put("id", credential.credentialId());
            if (credential.transports() != null && !credential.transports().isBlank()) {
                descriptor.put("transports", List.of(credential.transports().split(",")));
            }
            return descriptor;
        }).toList();
    }

    private static Set<AuthenticatorTransport> transports(String stored) {
        if (stored == null || stored.isBlank()) return Set.of();
        return Arrays.stream(stored.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .map(AuthenticatorTransport::create).collect(Collectors.toSet());
    }

    private static byte[] decode(String value) {
        if (value == null) throw new IllegalArgumentException("missing field");
        return B64D.decode(value);
    }
}

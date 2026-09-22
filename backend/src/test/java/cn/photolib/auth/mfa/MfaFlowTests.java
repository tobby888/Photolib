package cn.photolib.auth.mfa;

import cn.photolib.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.client.ClientPlatform;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 两步验证走完整过滤器链的行为：登录第二步、强制绑定、信任浏览器、敏感操作再验证、
 * 重置密码清设备，以及安全密钥（用 webauthn4j 的软件模拟器）。
 *
 * <p>整类在测试事务里跑，结束后回滚；唯一例外是失败计数——{@code LoginThrottle} 用独立事务
 * 写 {@code login_attempt}，会真的提交，所以 {@link #clearThrottleRows()} 在事务外把本类
 * 用到的计数删掉，免得污染别的测试类（共用同一个 H2 库）。
 */
@SpringBootTest
@Transactional
class MfaFlowTests {
    private static final long ADMIN_ID = 9_801L;
    private static final long MEMBER_ID = 9_802L;
    private static final long LOCKED_MEMBER_ID = 9_803L;
    private static final String PASSWORD = "Password-2fa-123";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Clock clock;
    @Autowired private UserService userService;
    @Autowired private MfaService mfaService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String hash = passwordEncoder.encode(PASSWORD);
        insertUser(ADMIN_ID, "mfa-admin", "ADMIN", hash);
        insertUser(MEMBER_ID, "mfa-member", "MINISTER", hash);
        insertUser(LOCKED_MEMBER_ID, "mfa-locked", "MINISTER", hash);
    }

    @AfterTransaction
    void clearThrottleRows() {
        jdbc.sql("DELETE FROM login_attempt WHERE attempt_key IN ('user:9801', 'user:9802', 'user:9803')").update();
    }

    @Test
    void passwordOnlyLoginIsUnchangedWhileTheSystemSwitchIsOff() throws Exception {
        JsonNode login = login("mfa-admin", null);
        assertThat(login.get("mfaRequired").asBoolean()).isFalse();
        assertThat(login.get("accessToken").asText()).isNotBlank();
        assertThat(login.at("/user/mfa/systemEnabled").asBoolean()).isFalse();
        assertThat(login.at("/user/mfa/policy").asText()).isEqualTo("REQUIRED");
    }

    @Test
    void enablingTheSystemRequiresTheAdministratorsOwnDevice() throws Exception {
        String token = login("mfa-admin", null).get("accessToken").asText();
        JsonNode refused = call(put("/api/v1/mfa-settings").content("{\"enabled\":true}"), token, 409);
        assertThat(refused.get("message").asText()).contains("绑定");

        enrollTotp(token);
        JsonNode enabled = call(put("/api/v1/mfa-settings").content("{\"enabled\":true}"), token, 200);
        assertThat(enabled.at("/data/enabled").asBoolean()).isTrue();
        assertThat(mfaService.systemEnabled()).isTrue();
    }

    @Test
    void aRequiredMemberIsConfinedToEnrollmentUntilADeviceIsBound() throws Exception {
        enableSystem();
        setMinisterPolicy("REQUIRED");
        JsonNode login = login("mfa-member", null);
        String token = login.get("accessToken").asText();
        assertThat(login.at("/user/mfa/enrollmentRequired").asBoolean()).isTrue();

        JsonNode blocked = call(get("/api/v1/projects"), token, 403);
        assertThat(blocked.get("code").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        call(get("/api/v1/auth/mfa"), token, 200);

        enrollTotp(token);
        JsonNode me = call(get("/api/v1/auth/me"), token, 200).get("data");
        assertThat(me.at("/mfa/enrollmentRequired").asBoolean()).isFalse();
        assertThat(me.at("/mfa/active").asBoolean()).isTrue();
        call(get("/api/v1/projects"), token, 200);
    }

    @Test
    void anAdminPasswordResetWipesDevicesAndEnrollmentComesBeforeTheNewPassword() throws Exception {
        enableSystem();
        setMinisterPolicy("REQUIRED");
        String secret = enrollTotp(login("mfa-member", null).get("accessToken").asText());
        jdbc.sql("""
                INSERT INTO mfa_trusted_device (user_id, token_hash, created_at, last_used_at, expires_at)
                VALUES (:id, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', :now, :now, :later)
                """).param("id", MEMBER_ID).param("now", LocalDateTime.now(clock))
                .param("later", LocalDateTime.now(clock).plusDays(10)).update();

        String initial = userService.resetPassword(MEMBER_ID);
        assertThat(count("mfa_device", MEMBER_ID)).isZero();
        assertThat(count("mfa_trusted_device", MEMBER_ID)).isZero();
        assertThat(secret).isNotBlank();

        JsonNode login = login("mfa-member", null, initial);
        String token = login.get("accessToken").asText();
        assertThat(login.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(login.at("/user/mfa/enrollmentRequired").asBoolean()).isTrue();
        JsonNode blocked = call(put("/api/v1/auth/initial-password").content(json.createObjectNode()
                .put("initialPassword", initial).put("newPassword", "Brand-new-pass-456").toString()), token, 403);
        assertThat(blocked.get("code").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");

        enrollTotp(token);
        call(put("/api/v1/auth/initial-password").content(json.createObjectNode()
                .put("initialPassword", initial).put("newPassword", "Brand-new-pass-456").toString()), token, 200);
    }

    @Test
    void anEnrolledLoginNeedsTheSecondStepAndATrustedBrowserSkipsIt() throws Exception {
        enableSystem();
        setMinisterPolicy("SUGGESTED");
        String secret = enrollTotp(login("mfa-member", null).get("accessToken").asText());

        MvcResult pending = loginResult("mfa-member", null, PASSWORD);
        JsonNode challenge = data(pending);
        assertThat(challenge.get("mfaRequired").asBoolean()).isTrue();
        assertThat(challenge.path("accessToken").asText("")).isEmpty();
        assertThat(challenge.get("mfaMethods").toString()).contains("TOTP");
        assertThat(pending.getResponse().getCookie("photolib_refresh")).isNull();
        String ticket = challenge.get("mfaTicket").asText();

        JsonNode wrong = verifyLogin(ticket, wrongCode(secret), false, 400);
        assertThat(wrong.get("code").asText()).isEqualTo("MFA_INVALID_CODE");

        MvcResult verified = mvc.perform(post("/api/v1/auth/login/mfa").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("ticket", ticket).put("code", code(secret, 0))
                        .put("trustDevice", true).toString())).andReturn();
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(verified).get("accessToken").asText()).isNotBlank();
        Cookie trusted = verified.getResponse().getCookie("photolib_mfa_trust_" + MEMBER_ID);
        assertThat(trusted).isNotNull();
        assertThat(trusted.getMaxAge()).isEqualTo(30 * 24 * 3600);
        assertThat(trusted.getPath()).isEqualTo("/api/v1/auth/login");
        assertThat(trusted.isHttpOnly()).isTrue();

        // 票据只能用一次。
        verifyLogin(ticket, code(secret, 1), false, 401);

        // 信任过的浏览器：密码对了直接拿到会话，Cookie 顺延。
        MvcResult again = loginResult("mfa-member", trusted, PASSWORD);
        assertThat(data(again).get("mfaRequired").asBoolean()).isFalse();
        assertThat(data(again).get("accessToken").asText()).isNotBlank();
        assertThat(again.getResponse().getCookie("photolib_mfa_trust_" + MEMBER_ID)).isNotNull();

        // 别的浏览器（没有 Cookie）仍然要第二步；别人的 Cookie 也不算数。
        assertThat(login("mfa-member", null).get("mfaRequired").asBoolean()).isTrue();
        Cookie forged = new Cookie("photolib_mfa_trust_" + MEMBER_ID, "not-a-real-token");
        assertThat(login("mfa-member", forged).get("mfaRequired").asBoolean()).isTrue();
    }

    @Test
    void trustedBrowsersSlideTheirExpiryAndExpiredOnesArePurged() {
        String token = mfaService.trustBrowser(MEMBER_ID, "test browser");
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.sql("UPDATE mfa_trusted_device SET expires_at = :soon WHERE user_id = :id")
                .param("soon", now.plusDays(1)).param("id", MEMBER_ID).update();
        assertThat(mfaService.useTrustedBrowser(MEMBER_ID, token)).isTrue();
        LocalDateTime slid = jdbc.sql("SELECT expires_at FROM mfa_trusted_device WHERE user_id = :id")
                .param("id", MEMBER_ID).query(LocalDateTime.class).single();
        assertThat(slid).isAfter(now.plusDays(29));
        // 令牌属于别的账号时不算数。
        assertThat(mfaService.useTrustedBrowser(ADMIN_ID, token)).isFalse();

        jdbc.sql("UPDATE mfa_trusted_device SET expires_at = :past WHERE user_id = :id")
                .param("past", now.minusMinutes(1)).param("id", MEMBER_ID).update();
        assertThat(mfaService.useTrustedBrowser(MEMBER_ID, token)).isFalse();
        mfaService.purgeExpired();
        assertThat(count("mfa_trusted_device", MEMBER_ID)).isZero();
    }

    @Test
    void sensitiveOperationsNeedAFreshVerificationThatSurvivesRefreshForFifteenMinutes() throws Exception {
        String setupToken = login("mfa-admin", null).get("accessToken").asText();
        String secret = enrollTotp(setupToken);
        call(put("/api/v1/mfa-settings").content("{\"enabled\":true}"), setupToken, 200);

        String ticket = login("mfa-admin", null).get("mfaTicket").asText();
        MvcResult verified = mvc.perform(post("/api/v1/auth/login/mfa").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("ticket", ticket).put("code", code(secret, 0)).toString()))
                .andReturn();
        String token = data(verified).get("accessToken").asText();

        JsonNode blocked = call(get("/api/v1/audit-logs"), token, 403);
        assertThat(blocked.get("code").asText()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(call(get("/api/v1/auth/mfa/step-up"), token, 200).at("/data/verified").asBoolean()).isFalse();

        // 登录时用掉的那个码不能再用一次。
        call(post("/api/v1/auth/mfa/step-up").content(codeBody(code(secret, 0))), token, 400);
        JsonNode stepUp = call(post("/api/v1/auth/mfa/step-up").content(codeBody(code(secret, 1))), token, 200);
        assertThat(stepUp.at("/data/verified").asBoolean()).isTrue();
        call(get("/api/v1/audit-logs"), token, 200);

        // 续期换了新会话，信任期跟着过去。
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh")
                .cookie(verified.getResponse().getCookie("photolib_refresh"))).andReturn();
        String refreshedToken = data(refreshed).get("accessToken").asText();
        call(get("/api/v1/audit-logs"), refreshedToken, 200);

        // 过了信任期就要重新验证。
        jdbc.sql("UPDATE auth_session SET step_up_until = :past WHERE user_id = :id")
                .param("past", LocalDateTime.now(clock).minusSeconds(1)).param("id", ADMIN_ID).update();
        call(get("/api/v1/audit-logs"), refreshedToken, 403);
    }

    @Test
    void deletesAreGuardedButOnlyForMembersWhoseTwoFactorIsActive() throws Exception {
        setMinisterPolicy("SUGGESTED");
        String token = login("mfa-member", null).get("accessToken").asText();
        // 没开全站开关：删除照常走业务判断（这里是不存在的选题）。
        call(delete("/api/v1/projects/987654321"), token, 404);

        enableSystem();
        JsonNode suggested = login("mfa-member", null);
        assertThat(suggested.at("/user/mfa/suggested").asBoolean()).isTrue();
        // 开了但还没绑定：无从再验证，照常放行。
        call(delete("/api/v1/projects/987654321"), suggested.get("accessToken").asText(), 404);

        String secret = enrollTotp(suggested.get("accessToken").asText());
        String ticket = login("mfa-member", null).get("mfaTicket").asText();
        String active = data(mvc.perform(post("/api/v1/auth/login/mfa").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("ticket", ticket).put("code", code(secret, 0)).toString()))
                .andReturn()).get("accessToken").asText();
        assertThat(call(delete("/api/v1/projects/987654321"), active, 403).get("code").asText())
                .isEqualTo("STEP_UP_REQUIRED");
        assertThat(call(delete("/api/v1/requests/987654321"), active, 403).get("code").asText())
                .isEqualTo("STEP_UP_REQUIRED");
        assertThat(call(delete("/api/v1/photos/987654321"), active, 403).get("code").asText())
                .isEqualTo("STEP_UP_REQUIRED");
        assertThat(call(post("/api/v1/photos/batch-delete").content("{\"photoIds\":[987654321]}"), active, 403)
                .get("code").asText()).isEqualTo("STEP_UP_REQUIRED");

        call(post("/api/v1/auth/mfa/step-up").content(codeBody(code(secret, 1))), active, 200);
        call(delete("/api/v1/projects/987654321"), active, 404);
    }

    @Test
    void repeatedWrongCodesLockTheSecondStepEvenWithTheRightCode() throws Exception {
        enableSystem();
        setMinisterPolicy("REQUIRED");
        String secret = enrollTotp(login("mfa-locked", null).get("accessToken").asText());
        String ticket = login("mfa-locked", null).get("mfaTicket").asText();
        for (int i = 0; i < 5; i++) {
            verifyLogin(ticket, wrongCode(secret), false, 400);
        }
        JsonNode locked = verifyLogin(ticket, code(secret, 0), false, 429);
        assertThat(locked.get("code").asText()).isEqualTo("RATE_LIMITED");
        // 重新输一遍密码也绕不过去：计数按账号记，不随密码登录清零。
        String fresh = login("mfa-locked", null).get("mfaTicket").asText();
        verifyLogin(fresh, code(secret, 0), false, 429);
    }

    @Test
    void aRequiredMemberCannotRemoveTheirLastDevice() throws Exception {
        enableSystem();
        setMinisterPolicy("REQUIRED");
        String token = login("mfa-member", null).get("accessToken").asText();
        String secret = enrollTotp(token);
        // 绑定完成后才是"生效"状态，删除设备要先再验证。
        call(post("/api/v1/auth/mfa/step-up").content(codeBody(code(secret, 1))), token, 200);
        Long deviceId = jdbc.sql("SELECT id FROM mfa_device WHERE user_id = :id").param("id", MEMBER_ID)
                .query(Long.class).single();
        JsonNode refused = call(delete("/api/v1/auth/mfa/devices/" + deviceId), token, 409);
        assertThat(refused.get("message").asText()).contains("最后一个");

        setMinisterPolicy("SUGGESTED");
        call(delete("/api/v1/auth/mfa/devices/" + deviceId), token, 200);
        assertThat(count("mfa_device", MEMBER_ID)).isZero();
    }

    @Test
    void aSecurityKeyRegistersAndSignsIn() throws Exception {
        enableSystem();
        setMinisterPolicy("REQUIRED");
        String token = login("mfa-member", null).get("accessToken").asText();
        ClientPlatform platform = EmulatorUtil.createClientPlatform(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR);
        platform.setOrigin(new Origin("http://localhost"));

        JsonNode options = call(post("/api/v1/auth/mfa/webauthn/options"), token, 200).get("data");
        JsonNode publicKey = options.get("publicKey");
        assertThat(publicKey.at("/rp/id").asText()).isEqualTo("localhost");
        PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput> created =
                platform.create(new PublicKeyCredentialCreationOptions(
                        new PublicKeyCredentialRpEntity(publicKey.at("/rp/id").asText(), "PhotoLib"),
                        new PublicKeyCredentialUserEntity(B64D.decode(publicKey.at("/user/id").asText()),
                                "mfa-member", "mfa-member"),
                        new DefaultChallenge(B64D.decode(publicKey.get("challenge").asText())),
                        List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                                COSEAlgorithmIdentifier.ES256)),
                        120_000L, List.of(),
                        new AuthenticatorSelectionCriteria(null, false, UserVerificationRequirement.DISCOURAGED),
                        AttestationConveyancePreference.NONE, null));
        ObjectNode register = json.createObjectNode()
                .put("challengeToken", options.get("challengeToken").asText())
                .put("name", "我的 USB 密钥")
                .put("clientDataJSON", B64.encodeToString(created.getResponse().getClientDataJSON()))
                .put("attestationObject", B64.encodeToString(created.getResponse().getAttestationObject()));
        register.putArray("transports").add("usb");
        JsonNode device = call(post("/api/v1/auth/mfa/webauthn").content(register.toString()), token, 200)
                .get("data");
        assertThat(device.get("type").asText()).isEqualTo("WEBAUTHN");
        assertThat(device.get("name").asText()).isEqualTo("我的 USB 密钥");
        // 同一个挑战不能再注册第二次。
        call(post("/api/v1/auth/mfa/webauthn").content(register.toString()), token, 400);

        JsonNode pending = login("mfa-member", null);
        assertThat(pending.get("mfaMethods").toString()).contains("WEBAUTHN");
        String ticket = pending.get("mfaTicket").asText();
        JsonNode request = data(mvc.perform(post("/api/v1/auth/login/mfa/webauthn-options")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("ticket", ticket).toString())).andReturn());
        assertThat(request.get("allowCredentials")).hasSize(1);
        List<PublicKeyCredentialDescriptor> allowed = new ArrayList<>();
        request.get("allowCredentials").forEach(item -> allowed.add(new PublicKeyCredentialDescriptor(
                PublicKeyCredentialType.PUBLIC_KEY, B64D.decode(item.get("id").asText()), null)));
        PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput> assertion =
                platform.get(new PublicKeyCredentialRequestOptions(
                        new DefaultChallenge(B64D.decode(request.get("challenge").asText())), 120_000L,
                        request.get("rpId").asText(), allowed, UserVerificationRequirement.DISCOURAGED, null));
        ObjectNode body = json.createObjectNode().put("ticket", ticket);
        body.putObject("assertion")
                .put("credentialId", B64.encodeToString(assertion.getRawId()))
                .put("clientDataJSON", B64.encodeToString(assertion.getResponse().getClientDataJSON()))
                .put("authenticatorData", B64.encodeToString(assertion.getResponse().getAuthenticatorData()))
                .put("signature", B64.encodeToString(assertion.getResponse().getSignature()));
        MvcResult signedIn = mvc.perform(post("/api/v1/auth/login/mfa").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andReturn();
        assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(signedIn).get("accessToken").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ helpers

    private void insertUser(long id, String username, String group, String hash) {
        jdbc.sql("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, permission_group_id,
                                      enabled, must_change_password)
                VALUES (:id, :username, :hash, :username, :role,
                        (SELECT id FROM permission_group WHERE code = :group), TRUE, FALSE)
                """).param("id", id).param("username", username).param("hash", hash)
                .param("role", group).param("group", group).update();
    }

    private void enableSystem() {
        jdbc.sql("UPDATE mfa_setting SET enabled = TRUE WHERE id = 1").update();
    }

    private void setMinisterPolicy(String policy) {
        jdbc.sql("UPDATE permission_group SET mfa_policy = :policy WHERE code = 'MINISTER'")
                .param("policy", policy).update();
    }

    private long count(String table, long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE user_id = :id").param("id", userId)
                .query(Long.class).single();
    }

    /** 走一遍绑定验证器 App，返回密钥。 */
    private String enrollTotp(String token) throws Exception {
        JsonNode setup = call(post("/api/v1/auth/mfa/totp"), token, 200).get("data");
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains(secret);
        call(post("/api/v1/auth/mfa/totp/" + setup.get("deviceId").asLong() + "/confirm")
                .content(json.createObjectNode().put("code", code(secret, -1)).put("name", "手机").toString()),
                token, 200);
        return secret;
    }

    private String code(String secret, int offset) {
        return Totp.code(secret, Totp.step(clock.instant().getEpochSecond()) + offset);
    }

    private String wrongCode(String secret) {
        long step = Totp.step(clock.instant().getEpochSecond());
        for (int candidate = 0; ; candidate++) {
            String value = String.format("%06d", candidate);
            if (!value.equals(Totp.code(secret, step - 1)) && !value.equals(Totp.code(secret, step))
                    && !value.equals(Totp.code(secret, step + 1)) && !value.equals(Totp.code(secret, step + 2))) {
                return value;
            }
        }
    }

    private String codeBody(String code) {
        return json.createObjectNode().put("code", code).toString();
    }

    private JsonNode login(String username, Cookie cookie) throws Exception {
        return login(username, cookie, PASSWORD);
    }

    private JsonNode login(String username, Cookie cookie, String password) throws Exception {
        MvcResult result = loginResult(username, cookie, password);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return data(result);
    }

    private MvcResult loginResult(String username, Cookie cookie, String password) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("username", username).put("password", password).toString());
        if (cookie != null) request.cookie(cookie);
        return mvc.perform(request).andReturn();
    }

    private JsonNode verifyLogin(String ticket, String code, boolean trust, int status) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login/mfa").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("ticket", ticket).put("code", code)
                        .put("trustDevice", trust).toString())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, int status) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }
}

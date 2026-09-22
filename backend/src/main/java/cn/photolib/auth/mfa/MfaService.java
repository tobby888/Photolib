package cn.photolib.auth.mfa;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.auth.MfaSummary;
import cn.photolib.auth.TokenSupport;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.notification.NotificationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 两步验证的全部业务规则：全站开关、设备绑定、登录第二步、信任浏览器、敏感操作再验证。
 *
 * <p>三种一次性票据（{@code mfa_challenge}）共用一套规则：浏览器只拿到随机令牌，库里只存
 * 哈希；过期或消费过的一律当作不存在；消费用带条件的 UPDATE 判定，并发的两次提交只有
 * 一次能成功。
 *
 * <p>这里不做失败计数：校验失败会抛异常、回滚本方法的事务，写在里面的计数会跟着一起
 * 回滚。计数由调用方在事务外交给 {@code LoginThrottle}，与登录限速是同一个道理。
 */
@Service
public class MfaService {
    public static final String TOTP = "TOTP";
    public static final String WEBAUTHN = "WEBAUTHN";
    static final String PURPOSE_LOGIN = "LOGIN";
    static final String PURPOSE_REGISTER = "REGISTER";
    static final String PURPOSE_STEP_UP = "STEP_UP";
    /** 生成了密钥却没确认的验证器，超过这个时间就要重新扫码。 */
    private static final Duration PENDING_TOTP_TTL = Duration.ofMinutes(30);
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_LABEL_LENGTH = 255;

    private final JdbcClient jdbc;
    private final MfaProperties properties;
    private final WebAuthnSupport webAuthn;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notifications;
    private final SecretCipher cipher;

    public MfaService(JdbcClient jdbc, MfaProperties properties, WebAuthnSupport webAuthn, Clock clock,
                      PasswordEncoder passwordEncoder, NotificationService notifications) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.webAuthn = webAuthn;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.cipher = new SecretCipher(properties.encryptionKey());
    }

    // ------------------------------------------------------------------ 全站开关

    public boolean systemEnabled() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT enabled FROM mfa_setting WHERE id = 1")
                .query(Boolean.class).optional().orElse(false));
    }

    public SettingsView settings() {
        return new SettingsView(systemEnabled(), cipher.available(),
                properties.rpId().isEmpty() ? null : properties.rpId());
    }

    /**
     * 打开全站开关的前提：
     * <ul>
     *   <li>服务器配好了加密密钥，否则没人能绑定验证器 App；</li>
     *   <li>操作的管理员自己已经绑好了设备，否则打开的那一刻就把自己锁在外面；</li>
     *   <li>再输一次密码：开关关着时绑定设备不需要再验证，偷到管理员会话的人可以先绑上
     *       自己的设备、再打开开关，把真管理员锁在外面。会话里没有密码，这一步挡得住他；</li>
     *   <li>本会话刚用设备验证过：证明设备确实在手上，也让这个会话算通过了第二步，
     *       开关打开后不会被当成"没过第二步的旧会话"踢下线。</li>
     * </ul>
     * 关掉开关不需要密码：此时两步验证已经生效，关开关本身就要先再验证。
     */
    @Transactional
    public SettingsView updateSettings(AuthenticatedUser admin, Long sessionId, boolean enabled, String password) {
        if (enabled && !systemEnabled()) {
            if (!cipher.available()) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "服务器未配置 MFA_ENCRYPTION_KEY，暂时不能启用两步验证");
            }
            if (confirmedDeviceCount(admin.id()) == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "请先在右上角头像菜单的「两步验证」里绑定自己的验证设备，再启用");
            }
            String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE id = :id")
                    .param("id", admin.id()).query(String.class).optional().orElse(null);
            // 400 而不是 401：前端遇到 401 会去续期会话，而这里只是密码输错了。
            if (hash == null || password == null || !passwordEncoder.matches(password, hash)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "密码不正确");
            }
            LocalDateTime until = stepUpUntil(sessionId);
            if (until == null || !until.isAfter(now())) {
                throw new BusinessException(ErrorCode.STEP_UP_REQUIRED, "启用前请先用你的验证设备确认一次");
            }
        }
        jdbc.sql("UPDATE mfa_setting SET enabled = :enabled, updated_by = :adminId, updated_at = :now WHERE id = 1")
                .param("enabled", enabled).param("adminId", admin.id()).param("now", now()).update();
        return settings();
    }

    /** 系统管理员组固定为强制：它是整套权限的入口，不能被设成"不使用"。 */
    public MfaSummary summarize(Long userId, String groupCode, MfaPolicy groupPolicy) {
        MfaPolicy policy = "ADMIN".equals(groupCode) ? MfaPolicy.REQUIRED : groupPolicy;
        return MfaSummary.of(systemEnabled(), policy, userId != null && confirmedDeviceCount(userId) > 0);
    }

    // ------------------------------------------------------------------ 设备

    public Overview overview(AuthenticatedUser user) {
        return new Overview(user.mfa(), devices(user.id()), trustedBrowsers(user.id()), cipher.available());
    }

    public List<DeviceView> devices(Long userId) {
        return jdbc.sql("""
                SELECT id, type, name, created_at, last_used_at FROM mfa_device
                WHERE user_id = :userId AND confirmed_at IS NOT NULL ORDER BY created_at, id
                """).param("userId", userId).query((rs, row) -> new DeviceView(rs.getLong("id"),
                rs.getString("type"), rs.getString("name"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("last_used_at", LocalDateTime.class))).list();
    }

    @Transactional
    public TotpSetup beginTotp(AuthenticatedUser user) {
        requireManageable(user);
        if (!cipher.available()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "服务器未配置 MFA_ENCRYPTION_KEY，暂时不能绑定验证器 App，请联系管理员");
        }
        // 上一次扫了码却没确认的那条作废：一个账号同时只留一份待确认的密钥。
        jdbc.sql("DELETE FROM mfa_device WHERE user_id = :userId AND type = 'TOTP' AND confirmed_at IS NULL")
                .param("userId", user.id()).update();
        String secret = Totp.newSecret();
        jdbc.sql("""
                INSERT INTO mfa_device (user_id, type, name, totp_secret_cipher, created_at)
                VALUES (:userId, 'TOTP', '验证器 App', :cipher, :now)
                """).param("userId", user.id()).param("cipher", cipher.encrypt(secret))
                .param("now", now()).update();
        Long id = jdbc.sql("""
                SELECT id FROM mfa_device WHERE user_id = :userId AND type = 'TOTP' AND confirmed_at IS NULL
                """).param("userId", user.id()).query(Long.class).single();
        return new TotpSetup(id, secret, Totp.uri(properties.rpName(), user.username(), secret));
    }

    @Transactional
    public DeviceView confirmTotp(AuthenticatedUser user, Long sessionId, Long deviceId, String code, String name) {
        requireManageable(user);
        Optional<String> stored = jdbc.sql("""
                SELECT totp_secret_cipher FROM mfa_device
                WHERE id = :id AND user_id = :userId AND type = 'TOTP' AND confirmed_at IS NULL
                  AND created_at > :freshAfter
                """).param("id", deviceId).param("userId", user.id())
                .param("freshAfter", now().minus(PENDING_TOTP_TTL)).query(String.class).optional();
        if (stored.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "绑定已过期，请重新扫码");
        }
        Long step = Totp.match(cipher.decrypt(stored.get()), normalizeCode(code), currentStep(), null);
        if (step == null) {
            throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "验证码不正确，请确认手机时间准确后重试");
        }
        int updated = jdbc.sql("""
                UPDATE mfa_device SET confirmed_at = :now, last_used_at = :now, last_totp_step = :step, name = :name
                WHERE id = :id AND confirmed_at IS NULL
                """).param("now", now()).param("step", step).param("name", deviceName(name, "验证器 App"))
                .param("id", deviceId).update();
        if (updated != 1) throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "该设备已经绑定过了");
        DeviceView device = device(user.id(), deviceId);
        afterDeviceAdded(user.id(), sessionId, device);
        return device;
    }

    /**
     * 刚输对一次新设备的码（或刚用新密钥签过名），本会话就算通过了第二步；同时告诉本人
     * 账号多了一个设备——不是本人绑的，就是会话被人拿去用了。
     */
    private void afterDeviceAdded(Long userId, Long sessionId, DeviceView device) {
        markSessionVerified(userId, sessionId);
        notifications.notifyUser(userId, "MFA_DEVICE_ADDED", "你的账号添加了两步验证设备",
                "<p>你的账号刚刚添加了验证设备「" + HtmlUtils.htmlEscape(device.name()) + "」。</p>"
                        + "<p>如果不是你本人操作，请立即联系管理员重置密码。</p>");
    }

    private void markSessionVerified(Long userId, Long sessionId) {
        if (sessionId == null) return;
        jdbc.sql("""
                UPDATE auth_session SET mfa_verified = TRUE
                WHERE id = :sessionId AND user_id = :userId AND revoked_at IS NULL
                """).param("sessionId", sessionId).param("userId", userId).update();
    }

    @Transactional
    public WebAuthnChallenge beginWebAuthnRegistration(AuthenticatedUser user, Long sessionId,
                                                       WebAuthnSupport.RelyingParty rp) {
        requireManageable(user);
        String challenge = WebAuthnSupport.newChallenge();
        String token = createChallenge(PURPOSE_REGISTER, user.id(), sessionId, challenge);
        return new WebAuthnChallenge(token, webAuthn.creationOptions(rp, challenge, user.id(), user.username(),
                user.displayName(), storedCredentials(user.id())));
    }

    @Transactional
    public DeviceView finishWebAuthnRegistration(AuthenticatedUser user, Long sessionId, String challengeToken,
                                                 String name, WebAuthnSupport.Attestation attestation,
                                                 WebAuthnSupport.RelyingParty rp) {
        requireManageable(user);
        String challenge = consumeChallenge(PURPOSE_REGISTER, challengeToken, user.id(), sessionId);
        WebAuthnSupport.RegisteredCredential credential = webAuthn.verifyRegistration(rp, challenge, attestation);
        LocalDateTime now = now();
        try {
            jdbc.sql("""
                    INSERT INTO mfa_device (user_id, type, name, credential_id, credential_data, sign_count,
                                            transports, confirmed_at, last_used_at, created_at)
                    VALUES (:userId, 'WEBAUTHN', :name, :credentialId, :data, :signCount, :transports, :now, :now, :now)
                    """).param("userId", user.id()).param("name", deviceName(name, "安全密钥"))
                    .param("credentialId", credential.credentialId()).param("data", credential.credentialData())
                    .param("signCount", credential.signCount()).param("transports", credential.transports())
                    .param("now", now).update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "这把安全密钥已经绑定过了");
        }
        Long id = jdbc.sql("SELECT id FROM mfa_device WHERE credential_id = :credentialId")
                .param("credentialId", credential.credentialId()).query(Long.class).single();
        DeviceView device = device(user.id(), id);
        afterDeviceAdded(user.id(), sessionId, device);
        return device;
    }

    /**
     * 删除一个验证设备。被强制两步验证的账号不能删掉最后一个——删了下一次请求就会被
     * 关进绑定流程，而且期间它的会话其实没有第二因素保护。
     */
    @Transactional
    public void deleteDevice(AuthenticatedUser user, Long deviceId) {
        // 按账号串行：否则同时删两个设备时，两边都数到"还剩 2 个"，结果一个不剩。
        jdbc.sql("SELECT id FROM app_user WHERE id = :id FOR UPDATE").param("id", user.id())
                .query(Long.class).optional();
        DeviceView device = device(user.id(), deviceId);
        long remaining = confirmedDeviceCount(user.id());
        if (remaining <= 1 && user.mfa().systemEnabled() && user.mfa().policy() == MfaPolicy.REQUIRED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "你所在的权限组要求两步验证，不能删除最后一个验证设备；请先绑定新设备再删除");
        }
        jdbc.sql("DELETE FROM mfa_device WHERE id = :id AND user_id = :userId")
                .param("id", device.id()).param("userId", user.id()).update();
        if (remaining <= 1) {
            // 一个设备都不剩了，信任过的浏览器也就没有意义；留着的话日后重新绑定时
            // 这些浏览器会直接跳过验证。
            jdbc.sql("DELETE FROM mfa_trusted_device WHERE user_id = :userId").param("userId", user.id()).update();
        }
        notifications.notifyUser(user.id(), "MFA_DEVICE_REMOVED", "你的账号删除了两步验证设备",
                "<p>你的账号刚刚删除了验证设备「" + HtmlUtils.htmlEscape(device.name()) + "」。</p>"
                        + "<p>如果不是你本人操作，请立即联系管理员重置密码。</p>");
    }

    /** 登录第二步被连续输错到锁定：走到第二步说明密码已经对了。 */
    public void warnSecondStepLocked(Long userId) {
        notifications.notifyUser(userId, "MFA_LOGIN_LOCKED", "有人用你的密码尝试登录",
                "<p>有人输入了你的正确密码，但两步验证连续失败，登录已被暂时锁定。</p>"
                        + "<p>如果不是你本人，说明密码可能已经泄露，请尽快修改密码或联系管理员。</p>");
    }

    /** 管理员重置密码时调用：设备、信任的浏览器、没用完的票据全部清掉。 */
    @Transactional
    public void resetForUser(Long userId) {
        jdbc.sql("DELETE FROM mfa_device WHERE user_id = :userId").param("userId", userId).update();
        jdbc.sql("DELETE FROM mfa_trusted_device WHERE user_id = :userId").param("userId", userId).update();
        jdbc.sql("DELETE FROM mfa_challenge WHERE user_id = :userId").param("userId", userId).update();
    }

    // ------------------------------------------------------------------ 信任的浏览器

    public List<TrustedBrowserView> trustedBrowsers(Long userId) {
        return jdbc.sql("""
                SELECT id, label, created_at, last_used_at, expires_at FROM mfa_trusted_device
                WHERE user_id = :userId AND expires_at > :now ORDER BY last_used_at DESC, id DESC
                """).param("userId", userId).param("now", now()).query((rs, row) -> new TrustedBrowserView(
                rs.getLong("id"), rs.getString("label"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("last_used_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class))).list();
    }

    @Transactional
    public void revokeTrustedBrowser(Long userId, Long id) {
        jdbc.sql("DELETE FROM mfa_trusted_device WHERE id = :id AND user_id = :userId")
                .param("id", id).param("userId", userId).update();
    }

    /** 记住这个浏览器，返回要写进 Cookie 的原始令牌（库里只存哈希）。 */
    @Transactional
    public String trustBrowser(Long userId, String label) {
        String raw = TokenSupport.randomToken();
        LocalDateTime now = now();
        jdbc.sql("""
                INSERT INTO mfa_trusted_device (user_id, token_hash, label, created_at, last_used_at, expires_at)
                VALUES (:userId, :hash, :label, :now, :now, :expiresAt)
                """).param("userId", userId).param("hash", TokenSupport.hash(raw))
                .param("label", truncate(label, MAX_LABEL_LENGTH)).param("now", now)
                .param("expiresAt", now.plus(properties.trustedDeviceTtl())).update();
        return raw;
    }

    /**
     * 凭信任令牌跳过第二步。命中就把有效期顺延一整个周期——"30 天内没有再登录才失效"，
     * 而不是从第一次信任起算 30 天。
     */
    @Transactional
    public boolean useTrustedBrowser(Long userId, String rawToken) {
        if (!StringUtils.hasText(rawToken)) return false;
        LocalDateTime now = now();
        return jdbc.sql("""
                UPDATE mfa_trusted_device SET last_used_at = :now, expires_at = :expiresAt
                WHERE token_hash = :hash AND user_id = :userId AND expires_at > :now
                """).param("now", now).param("expiresAt", now.plus(properties.trustedDeviceTtl()))
                .param("hash", TokenSupport.hash(rawToken)).param("userId", userId).update() == 1;
    }

    public Duration trustedBrowserTtl() {
        return properties.trustedDeviceTtl();
    }

    // ------------------------------------------------------------------ 登录第二步

    @Transactional
    public LoginChallenge startLogin(Long userId) {
        String ticket = createChallenge(PURPOSE_LOGIN, userId, null, null);
        List<String> methods = jdbc.sql("""
                SELECT DISTINCT type FROM mfa_device WHERE user_id = :userId AND confirmed_at IS NOT NULL
                """).param("userId", userId).query(String.class).list();
        return new LoginChallenge(ticket, methods, properties.challengeTtl().toSeconds());
    }

    /** 票据属于谁。无效、过期、用过的票据一律报同一句话。 */
    public Long loginTicketUser(String ticket) {
        return activeChallenge(PURPOSE_LOGIN, ticket).userId();
    }

    @Transactional
    public Map<String, Object> loginWebAuthnOptions(String ticket, WebAuthnSupport.RelyingParty rp) {
        ChallengeRow row = activeChallenge(PURPOSE_LOGIN, ticket);
        String challenge = WebAuthnSupport.newChallenge();
        jdbc.sql("UPDATE mfa_challenge SET webauthn_challenge = :challenge WHERE id = :id")
                .param("challenge", challenge).param("id", row.id()).update();
        return webAuthn.requestOptions(rp, challenge, storedCredentials(row.userId()));
    }

    /** 校验第二步并消费票据，返回登录的账号。 */
    @Transactional
    public Long completeLogin(String ticket, Verification verification, WebAuthnSupport.RelyingParty rp) {
        ChallengeRow row = activeChallenge(PURPOSE_LOGIN, ticket);
        verifyFactor(row.userId(), verification, row.webauthnChallenge(), rp);
        if (!consume(row.id())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证已过期，请重新登录");
        }
        return row.userId();
    }

    // ------------------------------------------------------------------ 敏感操作再验证

    public StepUpStatus stepUpStatus(AuthenticatedUser user, Long sessionId) {
        if (!user.mfa().active()) return new StepUpStatus(false, true, null);
        LocalDateTime until = stepUpUntil(sessionId);
        boolean verified = until != null && until.isAfter(now());
        return new StepUpStatus(true, verified, verified ? until : null);
    }

    /** 两步验证没对这个账号生效时一律放行：没有设备的人无从再验证。 */
    public boolean stepUpSatisfied(AuthenticatedUser user, Long sessionId) {
        return stepUpStatus(user, sessionId).verified();
    }

    @Transactional
    public WebAuthnChallenge stepUpWebAuthnOptions(AuthenticatedUser user, Long sessionId,
                                                   WebAuthnSupport.RelyingParty rp) {
        String challenge = WebAuthnSupport.newChallenge();
        String token = createChallenge(PURPOSE_STEP_UP, user.id(), sessionId, challenge);
        return new WebAuthnChallenge(token, webAuthn.requestOptions(rp, challenge, storedCredentials(user.id())));
    }

    @Transactional
    public StepUpStatus completeStepUp(AuthenticatedUser user, Long sessionId, Verification verification,
                                       WebAuthnSupport.RelyingParty rp) {
        if (sessionId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "会话已失效");
        String challenge = verification.assertion() == null ? null
                : consumeChallenge(PURPOSE_STEP_UP, verification.challengeToken(), user.id(), sessionId);
        verifyFactor(user.id(), verification, challenge, rp);
        LocalDateTime until = now().plus(properties.stepUpTtl());
        jdbc.sql("""
                UPDATE auth_session SET step_up_until = :until, mfa_verified = TRUE
                WHERE id = :sessionId AND user_id = :userId AND revoked_at IS NULL
                """).param("until", until).param("sessionId", sessionId).param("userId", user.id()).update();
        return new StepUpStatus(true, true, until);
    }

    private LocalDateTime stepUpUntil(Long sessionId) {
        if (sessionId == null) return null;
        return jdbc.sql("SELECT step_up_until FROM auth_session WHERE id = :id")
                .param("id", sessionId).query(LocalDateTime.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ 校验

    private void verifyFactor(Long userId, Verification verification, String webauthnChallenge,
                              WebAuthnSupport.RelyingParty rp) {
        if (verification == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入验证码");
        if (verification.assertion() != null) {
            verifyAssertion(userId, verification.assertion(), webauthnChallenge, rp);
            return;
        }
        String code = normalizeCode(verification.code());
        if (code.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入验证码");
        long step = currentStep();
        List<TotpRow> devices = jdbc.sql("""
                SELECT id, totp_secret_cipher, last_totp_step FROM mfa_device
                WHERE user_id = :userId AND type = 'TOTP' AND confirmed_at IS NOT NULL
                """).param("userId", userId).query((rs, row) -> new TotpRow(rs.getLong("id"),
                rs.getString("totp_secret_cipher"), (Long) rs.getObject("last_totp_step", Long.class))).list();
        for (TotpRow device : devices) {
            Long matched = Totp.match(cipher.decrypt(device.cipher()), code, step, device.lastStep());
            if (matched == null) continue;
            // 条件更新保证同一个码只能用一次，哪怕两次提交几乎同时到达。
            int updated = jdbc.sql("""
                    UPDATE mfa_device SET last_totp_step = :step, last_used_at = :now
                    WHERE id = :id AND (last_totp_step IS NULL OR last_totp_step < :step)
                    """).param("step", matched).param("now", now()).param("id", device.id()).update();
            if (updated == 1) return;
        }
        throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "验证码不正确或已使用过");
    }

    private void verifyAssertion(Long userId, WebAuthnSupport.Assertion assertion, String challenge,
                                 WebAuthnSupport.RelyingParty rp) {
        if (challenge == null) {
            throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "请重新点击「使用安全密钥」");
        }
        Optional<WebAuthnRow> device = jdbc.sql("""
                SELECT id, credential_id, credential_data, sign_count, transports FROM mfa_device
                WHERE user_id = :userId AND type = 'WEBAUTHN' AND confirmed_at IS NOT NULL
                  AND credential_id = :credentialId
                """).param("userId", userId).param("credentialId", assertion.credentialId())
                .query((rs, row) -> new WebAuthnRow(rs.getLong("id"), new WebAuthnSupport.StoredCredential(
                        rs.getString("credential_id"), rs.getBytes("credential_data"),
                        rs.getLong("sign_count"), rs.getString("transports")))).optional();
        if (device.isEmpty()) {
            throw new BusinessException(ErrorCode.MFA_INVALID_CODE, "这把安全密钥没有绑定到当前账号");
        }
        long signCount = webAuthn.verifyAssertion(rp, challenge, device.get().credential(), assertion);
        jdbc.sql("UPDATE mfa_device SET sign_count = :signCount, last_used_at = :now WHERE id = :id")
                .param("signCount", signCount).param("now", now()).param("id", device.get().id()).update();
    }

    // ------------------------------------------------------------------ 票据

    private String createChallenge(String purpose, Long userId, Long sessionId, String webauthnChallenge) {
        String raw = TokenSupport.randomToken();
        LocalDateTime now = now();
        jdbc.sql("""
                INSERT INTO mfa_challenge (purpose, token_hash, user_id, session_id, webauthn_challenge,
                                           expires_at, created_at)
                VALUES (:purpose, :hash, :userId, :sessionId, :challenge, :expiresAt, :now)
                """).param("purpose", purpose).param("hash", TokenSupport.hash(raw)).param("userId", userId)
                .param("sessionId", sessionId).param("challenge", webauthnChallenge)
                .param("expiresAt", now.plus(properties.challengeTtl())).param("now", now).update();
        return raw;
    }

    private ChallengeRow activeChallenge(String purpose, String raw) {
        if (!StringUtils.hasText(raw)) throw expired(purpose);
        return jdbc.sql("""
                SELECT id, user_id, session_id, webauthn_challenge FROM mfa_challenge
                WHERE token_hash = :hash AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now
                """).param("hash", TokenSupport.hash(raw)).param("purpose", purpose).param("now", now())
                .query((rs, row) -> new ChallengeRow(rs.getLong("id"), rs.getLong("user_id"),
                        (Long) rs.getObject("session_id", Long.class), rs.getString("webauthn_challenge")))
                .optional().orElseThrow(() -> expired(purpose));
    }

    /** 消费一张属于这个账号、这个会话的票据，返回其中的 WebAuthn 挑战值。 */
    private String consumeChallenge(String purpose, String raw, Long userId, Long sessionId) {
        ChallengeRow row = activeChallenge(purpose, raw);
        if (!row.userId().equals(userId) || (row.sessionId() != null && !row.sessionId().equals(sessionId))
                || !consume(row.id())) {
            throw expired(purpose);
        }
        return row.webauthnChallenge();
    }

    private boolean consume(Long id) {
        return jdbc.sql("UPDATE mfa_challenge SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
                .param("now", now()).param("id", id).update() == 1;
    }

    private static BusinessException expired(String purpose) {
        return PURPOSE_LOGIN.equals(purpose)
                ? new BusinessException(ErrorCode.UNAUTHORIZED, "验证已过期，请重新登录")
                : new BusinessException(ErrorCode.MFA_INVALID_CODE, "验证已过期，请重试");
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @Transactional
    public void purgeExpired() {
        LocalDateTime now = now();
        jdbc.sql("DELETE FROM mfa_trusted_device WHERE expires_at <= :now").param("now", now).update();
        jdbc.sql("DELETE FROM mfa_challenge WHERE expires_at <= :before")
                .param("before", now.minusDays(1)).update();
        jdbc.sql("DELETE FROM mfa_device WHERE confirmed_at IS NULL AND created_at <= :before")
                .param("before", now.minusDays(1)).update();
    }

    // ------------------------------------------------------------------ 杂项

    private void requireManageable(AuthenticatedUser user) {
        if (user.mfa().policy() == MfaPolicy.OFF) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你所在的权限组未启用两步验证");
        }
    }

    private DeviceView device(Long userId, Long deviceId) {
        return devices(userId).stream().filter(device -> device.id().equals(deviceId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "验证设备不存在"));
    }

    private long confirmedDeviceCount(Long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM mfa_device WHERE user_id = :userId AND confirmed_at IS NOT NULL")
                .param("userId", userId).query(Long.class).single();
    }

    private List<WebAuthnSupport.StoredCredential> storedCredentials(Long userId) {
        return jdbc.sql("""
                SELECT credential_id, credential_data, sign_count, transports FROM mfa_device
                WHERE user_id = :userId AND type = 'WEBAUTHN' AND confirmed_at IS NOT NULL
                """).param("userId", userId).query((rs, row) -> new WebAuthnSupport.StoredCredential(
                rs.getString("credential_id"), rs.getBytes("credential_data"), rs.getLong("sign_count"),
                rs.getString("transports"))).list();
    }

    private long currentStep() {
        return Totp.step(clock.instant().getEpochSecond());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 验证器 App 常把 6 位数显示成 "123 456"，复制过来带空格也要认。 */
    private static String normalizeCode(String code) {
        return code == null ? "" : code.replaceAll("\\s", "");
    }

    private static String deviceName(String name, String fallback) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? fallback : truncate(trimmed, MAX_NAME_LENGTH);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private record ChallengeRow(Long id, Long userId, Long sessionId, String webauthnChallenge) {
    }

    private record TotpRow(Long id, String cipher, Long lastStep) {
    }

    private record WebAuthnRow(Long id, WebAuthnSupport.StoredCredential credential) {
    }

    /** 第二步的凭据：验证器 App 的 6 位码，或安全密钥的断言（再验证时带上票据）。 */
    public record Verification(String code, WebAuthnSupport.Assertion assertion, String challengeToken) {
    }

    public record SettingsView(boolean enabled, boolean totpAvailable, String webauthnRpId) {
    }

    public record Overview(MfaSummary summary, List<DeviceView> devices, List<TrustedBrowserView> trustedBrowsers,
                           boolean totpAvailable) {
    }

    public record DeviceView(Long id, String type, String name, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
    }

    public record TrustedBrowserView(Long id, String label, LocalDateTime createdAt, LocalDateTime lastUsedAt,
                                     LocalDateTime expiresAt) {
    }

    public record TotpSetup(Long deviceId, String secret, String otpauthUri) {
    }

    public record WebAuthnChallenge(String challengeToken, Map<String, Object> publicKey) {
    }

    public record LoginChallenge(String ticket, List<String> methods, long expiresIn) {
    }

    public record StepUpStatus(boolean required, boolean verified, LocalDateTime verifiedUntil) {
    }
}

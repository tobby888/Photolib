package cn.photolib.mcp;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.mcp.mapper.McpAuthRequestMapper;
import cn.photolib.mcp.model.McpAuthRequestEntity;
import cn.photolib.mcp.model.McpAuthStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * MCP 客户端的"在浏览器里登录"。
 *
 * <p>MCP 服务跑在成员自己的机器上，那里没有浏览器会话。让它直接拿账号密码是最差的
 * 选择：密码要写进宿主（Claude Desktop / Claude Code）的配置文件，而那份文件会被同步、
 * 被截图、被贴进群里，且一旦泄漏就是完整的账号，不是一个可以单独吊销的会话。所以这里
 * 走设备码模式——客户端只保管一份能被单独吊销的令牌，密码自始至终只出现在浏览器的登录页上。
 *
 * <h2>一次配对的完整过程</h2>
 * <ol>
 *   <li>客户端 {@link #open} 开一条记录，拿到 {@code requestId}（公开）、
 *       {@code deviceCode}（客户端私有）和 {@code userCode}（打印在终端上给人看）；</li>
 *   <li>成员在浏览器里打开 {@code /mcp/authorize?request=<requestId>}，
 *       用已经登录的会话看到"是谁、从哪台机器、什么时候发起的"（{@link #describe}）；</li>
 *   <li>成员把终端上那串配对码**手敲**进批准页，{@link #approve} 校验通过后记录转为已批准；</li>
 *   <li>客户端一直 {@link #poll}，批准后换走一对令牌，记录转为已消费。</li>
 * </ol>
 *
 * <h2>为什么批准页要手敲配对码</h2>
 * <p>只凭链接批准是有洞的：攻击者在自己机器上发起配对，把那条链接发给部长，部长登录着、
 * 顺手一点，攻击者就拿到了一个以部长身份说话的令牌。配对码堵的正是这条路——它只出现在
 * 发起配对的那个终端上，攻击者拿不到受害者终端上的码，受害者手里没有码也就点不动批准。
 * 因此 {@link #describe} 绝不返回配对码，库里存的也只是它的哈希：批准页自己都不知道
 * 正确答案，"手敲一遍"才不会退化成走过场。同样的道理，猜错 {@value #MAX_FAILED_ATTEMPTS}
 * 次这条记录就地作废——八位码经不起无限猜。
 *
 * <p>令牌本身没有任何特殊之处：它就是一次普通登录会签发的会话（{@link AuthService}），
 * 权限、校区范围、初始密码限制、停用与改密后的失效全都照旧。**MCP 不是一个新的权限边界**，
 * 它只是同一个成员的另一个客户端。
 */
@Service
@RequiredArgsConstructor
public class McpAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(McpAuthorizationService.class);

    /** 配对窗口。够成员切到浏览器、登录、手敲一次码，又短到丢在剪贴板里的链接很快就没用。 */
    public static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    /** 建议客户端的轮询间隔，随发起应答一起下发。 */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    /** 配对码允许猜错的次数。 */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    /** 批准页的前端路由，客户端用自己配置的站点地址把它拼成完整链接。 */
    public static final String VERIFICATION_PATH = "/mcp/authorize";

    /**
     * 配对码的字母表。去掉了 {@code I L O U 0 1}：这串码要靠人眼从终端读、用手敲进浏览器，
     * 形近字符每一个都是一次"明明敲对了却说错"的无效尝试，而尝试次数是有上限的。
     */
    private static final char[] USER_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789".toCharArray();
    private static final int USER_CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpAuthRequestMapper mapper;
    private final AuthService authService;
    /** 应用统一的 Asia/Shanghai 时钟；注入进来好让测试固定时间。 */
    private final Clock clock;

    /** 发起一次配对。匿名可调：此刻还没有任何身份，身份是批准那一步才绑上的。 */
    @Transactional
    public Pairing open(String clientName, String deviceLabel, String remoteAddress) {
        String requestId = randomToken();
        String deviceCode = randomToken();
        String userCode = randomUserCode();
        LocalDateTime now = LocalDateTime.now(clock);

        McpAuthRequestEntity entity = new McpAuthRequestEntity();
        entity.setRequestId(requestId);
        entity.setDeviceCodeHash(hash(deviceCode));
        entity.setUserCodeHash(hash(normalizeUserCode(userCode)));
        entity.setClientName(trim(clientName, 100, "MCP 客户端"));
        entity.setDeviceLabel(trim(deviceLabel, 100, null));
        entity.setRequestedIp(trim(remoteAddress, 64, null));
        entity.setStatus(McpAuthStatus.PENDING);
        entity.setFailedAttempts(0);
        entity.setExpiresAt(now.plus(REQUEST_TTL));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);

        return new Pairing(requestId, deviceCode, userCode, VERIFICATION_PATH,
                REQUEST_TTL.toSeconds(), POLL_INTERVAL.toSeconds());
    }

    /**
     * 批准页要展示的内容。只给"在决定批不批时需要知道的东西"：谁发起的、从哪个地址、
     * 什么时候到期。配对码不在其中，理由见类注释。
     */
    public PendingView describe(String requestId) {
        McpAuthRequestEntity entity = require(requestId);
        return new PendingView(entity.getRequestId(), entity.getClientName(), entity.getDeviceLabel(),
                entity.getRequestedIp(), effectiveStatus(entity), entity.getCreatedAt(),
                entity.getExpiresAt(), MAX_FAILED_ATTEMPTS - failedAttempts(entity));
    }

    /**
     * 成员批准配对。{@code approver} 是浏览器里那个已登录的会话，客户端最终拿到的
     * 就是以它的身份签发的令牌。
     *
     * <p><b>刻意不加 {@code @Transactional}。</b>猜错配对码要先记一笔再抛异常，而异常会
     * 把本方法的事务整个回滚——连那一笔计数一起。计数器归零，{@value #MAX_FAILED_ATTEMPTS}
     * 次上限也就形同虚设，八位码可以无限猜。{@code AuthController.login} 为同一个原因
     * 把登录限速放在了事务之外。这里不需要事务：状态迁移本来就是一条带条件的 UPDATE，
     * 原子性由数据库保证，见 {@code McpAuthRequestMapper} 的类注释。
     */
    public void approve(String requestId, String submittedUserCode, AuthenticatedUser approver) {
        McpAuthRequestEntity entity = require(requestId);
        requirePending(entity);
        if (!constantTimeEquals(entity.getUserCodeHash(), hash(normalizeUserCode(submittedUserCode)))) {
            mapper.recordFailedAttempt(requestId, MAX_FAILED_ATTEMPTS, LocalDateTime.now(clock));
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "配对码不正确，请核对终端上显示的配对码");
        }
        if (mapper.approve(requestId, approver.id(), LocalDateTime.now(clock)) != 1) {
            // 中间有人抢先批准/拒绝，或者刚好卡在过期那一刻。重查一次，把真实原因报出去。
            requirePending(require(requestId));
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "配对请求状态已变化，请重新发起");
        }
        log.info("MCP 配对已批准: requestId={} 客户端={} 批准人={}",
                requestId, entity.getClientName(), approver.username());
    }

    /** 成员拒绝配对。客户端会立刻看到拒绝，而不是一直轮询到超时。 */
    @Transactional
    public void deny(String requestId, AuthenticatedUser user) {
        McpAuthRequestEntity entity = require(requestId);
        requirePending(entity);
        mapper.deny(requestId, user.id(), LocalDateTime.now(clock));
    }

    /**
     * 客户端轮询。还没批准就如实回 {@code PENDING}，批准了就把令牌换走。
     *
     * <p>{@code deviceCode} 不对一律当作"没有这条记录"：地址栏里的 {@code requestId}
     * 是公开的，如果错误的凭据能换来一个可分辨的错误，它就成了"这个配对存在吗"的探针。
     */
    @Transactional
    public Grant poll(String requestId, String deviceCode) {
        McpAuthRequestEntity entity = mapper.selectOne(Wrappers.<McpAuthRequestEntity>lambdaQuery()
                .eq(McpAuthRequestEntity::getRequestId, requestId));
        if (entity == null || !constantTimeEquals(entity.getDeviceCodeHash(), hash(deviceCode))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "配对请求不存在或已过期");
        }
        McpAuthStatus status = effectiveStatus(entity);
        return switch (status) {
            case PENDING -> new Grant(McpAuthStatus.PENDING, null, null, 0, null);
            case DENIED -> throw new BusinessException(ErrorCode.FORBIDDEN, "配对请求已被拒绝或已作废");
            case CONSUMED -> throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "配对请求已经换取过令牌，请重新发起");
            case APPROVED -> {
                if (mapper.consume(requestId, LocalDateTime.now(clock)) != 1) {
                    // 同一条记录被并发轮询两次，只有一个能赢。输的那个不该也拿到一份令牌。
                    throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                            "配对请求已经换取过令牌，请重新发起");
                }
                AuthService.TokenPair pair = authService.issueForPairedClient(entity.getUserId());
                yield new Grant(McpAuthStatus.APPROVED, pair.accessToken(), pair.refreshToken(),
                        pair.expiresIn(), pair.user());
            }
        };
    }

    /**
     * 过期记录的清理。宽限一天再删，好让刚过期的客户端读到"已过期"而不是"不存在"——
     * 两者给出的下一步操作是一样的，但前者不会让人怀疑是自己抄错了链接。
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpired() {
        int removed = mapper.deleteExpiredBefore(LocalDateTime.now(clock).minusDays(1));
        if (removed > 0) log.info("清理过期的 MCP 配对请求 {} 条", removed);
    }

    private McpAuthRequestEntity require(String requestId) {
        McpAuthRequestEntity entity = mapper.selectOne(Wrappers.<McpAuthRequestEntity>lambdaQuery()
                .eq(McpAuthRequestEntity::getRequestId, requestId));
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "配对请求不存在或已过期");
        }
        return entity;
    }

    private void requirePending(McpAuthRequestEntity entity) {
        McpAuthStatus status = effectiveStatus(entity);
        if (status != McpAuthStatus.PENDING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, switch (status) {
                case APPROVED -> "该配对请求已经批准过了";
                case CONSUMED -> "该配对请求已经完成";
                default -> "该配对请求已被拒绝或已过期，请在客户端重新发起";
            });
        }
    }

    /** 过期的 {@code PENDING} 对外一律表现为 {@code DENIED}：能不能继续，只有这一个答案。 */
    private McpAuthStatus effectiveStatus(McpAuthRequestEntity entity) {
        boolean expired = !entity.getExpiresAt().isAfter(LocalDateTime.now(clock));
        return expired && entity.getStatus() == McpAuthStatus.PENDING
                ? McpAuthStatus.DENIED : entity.getStatus();
    }

    private static int failedAttempts(McpAuthRequestEntity entity) {
        return entity.getFailedAttempts() == null ? 0 : entity.getFailedAttempts();
    }

    private static String trim(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /** 比对时忽略大小写和分隔符：终端打印成 {@code ABCD-EFGH}，人可能连横杠一起敲进来。 */
    static String normalizeUserCode(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder(USER_CODE_LENGTH);
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c)) normalized.append(Character.toUpperCase(c));
        }
        return normalized.toString();
    }

    static String formatUserCode(String raw) {
        return raw.substring(0, 4) + "-" + raw.substring(4);
    }

    private static String randomUserCode() {
        StringBuilder code = new StringBuilder(USER_CODE_LENGTH);
        for (int i = 0; i < USER_CODE_LENGTH; i++) {
            code.append(USER_CODE_ALPHABET[RANDOM.nextInt(USER_CODE_ALPHABET.length)]);
        }
        return formatUserCode(code.toString());
    }

    private static String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                right.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 发起配对的应答。{@code verificationPath} 是前端路由而不是完整链接：客户端本来就
     * 配置着站点地址，由它来拼；服务端若改用请求头里的 Host 去猜，反而多出一个可以被
     * 伪造的来源。
     */
    public record Pairing(String requestId, String deviceCode, String userCode,
                          String verificationPath, long expiresIn, long interval) {
    }

    public record PendingView(String requestId, String clientName, String deviceLabel,
                              String requestedIp, McpAuthStatus status, LocalDateTime createdAt,
                              LocalDateTime expiresAt, int remainingAttempts) {
    }

    public record Grant(McpAuthStatus status, String accessToken, String refreshToken,
                        long expiresIn, AuthenticatedUser user) {
    }
}

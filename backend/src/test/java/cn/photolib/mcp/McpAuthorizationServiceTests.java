package cn.photolib.mcp;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.mcp.mapper.McpAuthRequestMapper;
import cn.photolib.mcp.model.McpAuthRequestEntity;
import cn.photolib.mcp.model.McpAuthStatus;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 配对（"在浏览器里登录"）的服务层行为。
 *
 * <p>重点不在"顺利走完一遍"，而在那些让这套流程不至于变成后门的约束：配对码必须手敲
 * 且猜不了几次、批准页拿不到配对码、一条记录只能换一次令牌、设备凭据不对一律当作
 * 不存在。设计理由见 {@link McpAuthorizationService} 的类注释。
 */
@SpringBootTest
@Transactional
class McpAuthorizationServiceTests {
    @Autowired private McpAuthorizationService service;
    @Autowired private McpAuthRequestMapper mapper;
    @Autowired private AuthService authService;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private UserEntity member;
    private AuthenticatedUser principal;

    @BeforeEach
    void setUp() {
        member = new UserEntity();
        member.setUsername("mcp-member");
        member.setPasswordHash(passwordEncoder.encode("mcpPassword123"));
        member.setDisplayName("配对成员");
        member.setRole(UserRole.MINISTER);
        member.setEnabled(true);
        member.setMustChangePassword(false);
        userMapper.insert(member);
        principal = new AuthenticatedUser(member.getId(), member.getUsername(),
                member.getDisplayName(), UserRole.MINISTER, null, false);
    }

    @Test
    void approvedPairing_issuesAWorkingSessionForTheApprover() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", "本机", "127.0.0.1");

        service.approve(pairing.requestId(), pairing.userCode(), principal);
        McpAuthorizationService.Grant grant = service.poll(pairing.requestId(), pairing.deviceCode());

        assertThat(grant.status()).isEqualTo(McpAuthStatus.APPROVED);
        assertThat(grant.user().id()).isEqualTo(member.getId());
        // 令牌必须是一次普通登录会签发的会话，而不是什么特殊通道。
        assertThat(authService.authenticate(grant.accessToken())).isNotNull();
        assertThat(authService.authenticate(grant.accessToken()).user().id()).isEqualTo(member.getId());
    }

    @Test
    void pollBeforeApproval_reportsPendingRatherThanFailing() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");

        McpAuthorizationService.Grant grant = service.poll(pairing.requestId(), pairing.deviceCode());

        assertThat(grant.status()).isEqualTo(McpAuthStatus.PENDING);
        assertThat(grant.accessToken()).isNull();
    }

    /** 一条记录只能换一次令牌：重放同一份设备凭据拿不到第二个会话。 */
    @Test
    void secondPoll_afterTokensWereTaken_isRejected() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");
        service.approve(pairing.requestId(), pairing.userCode(), principal);
        service.poll(pairing.requestId(), pairing.deviceCode());

        assertThatThrownBy(() -> service.poll(pairing.requestId(), pairing.deviceCode()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已经换取过令牌");
    }

    /**
     * 设备凭据不对一律报"不存在"。{@code requestId} 出现在浏览器地址栏里，如果错误的
     * 凭据能换来一个可分辨的错误，它就成了"这个配对存在吗"的探针。
     */
    @Test
    void wrongDeviceCode_looksExactlyLikeAMissingRequest() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");
        service.approve(pairing.requestId(), pairing.userCode(), principal);

        assertThatThrownBy(() -> service.poll(pairing.requestId(), "not-the-device-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或已过期");
        assertThatThrownBy(() -> service.poll("no-such-request", "not-the-device-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或已过期");
    }

    /** 批准页不该知道正确答案，否则"手敲一遍配对码"就退化成了走过场。 */
    @Test
    void describe_neverExposesTheUserCode() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", "本机", "10.0.0.8");

        McpAuthorizationService.PendingView view = service.describe(pairing.requestId());

        assertThat(view.clientName()).isEqualTo("Claude Code");
        assertThat(view.deviceLabel()).isEqualTo("本机");
        assertThat(view.requestedIp()).isEqualTo("10.0.0.8");
        assertThat(view.status()).isEqualTo(McpAuthStatus.PENDING);
        assertThat(view.toString()).doesNotContain(pairing.userCode());
    }

    @Test
    void wrongUserCode_isRefusedAndCounted() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");

        assertThatThrownBy(() -> service.approve(pairing.requestId(), "AAAA-AAAA", principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("配对码不正确");

        assertThat(service.describe(pairing.requestId()).remainingAttempts())
                .isEqualTo(McpAuthorizationService.MAX_FAILED_ATTEMPTS - 1);
        // 猜错不该顺手把这条记录批准掉，也不该让它离开待批准状态。
        assertThat(service.poll(pairing.requestId(), pairing.deviceCode()).status())
                .isEqualTo(McpAuthStatus.PENDING);
    }

    /** 八位配对码经不起无限猜，猜满上限这条记录就地作废。 */
    @Test
    void guessingTheUserCode_killsTheRequest() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");

        for (int attempt = 0; attempt < McpAuthorizationService.MAX_FAILED_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> service.approve(pairing.requestId(), "AAAA-AAAA", principal))
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(service.describe(pairing.requestId()).status()).isEqualTo(McpAuthStatus.DENIED);
        // 作废之后，即使拿着正确的配对码也批不动了。
        assertThatThrownBy(() -> service.approve(pairing.requestId(), pairing.userCode(), principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被拒绝");
    }

    /** 大小写和分隔符不该成为无效尝试：码是人从终端读出来再敲进去的。 */
    @Test
    void userCodeComparison_ignoresCaseAndSeparators() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");
        String messyCode = pairing.userCode().replace("-", " ").toLowerCase();

        service.approve(pairing.requestId(), messyCode, principal);

        assertThat(service.describe(pairing.requestId()).status()).isEqualTo(McpAuthStatus.APPROVED);
    }

    @Test
    void deniedPairing_tellsTheClientToStopPolling() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");

        service.deny(pairing.requestId(), principal);

        assertThatThrownBy(() -> service.poll(pairing.requestId(), pairing.deviceCode()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被拒绝");
    }

    /** 过期的待批准记录既批不动也换不到令牌，即使配对码和设备凭据都对。 */
    @Test
    void expiredPairing_cannotBeApprovedOrRedeemed() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");
        expire(pairing.requestId());

        assertThatThrownBy(() -> service.approve(pairing.requestId(), pairing.userCode(), principal))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.poll(pairing.requestId(), pairing.deviceCode()))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 批准和取令牌之间隔着一段时间，账号可能在这中间被停用。那时不能再签发会话——
     * 否则停用一个账号还得记得去翻有没有待取的配对。
     */
    @Test
    void disabledAccount_betweenApprovalAndPoll_getsNoSession() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");
        service.approve(pairing.requestId(), pairing.userCode(), principal);

        member.setEnabled(false);
        userMapper.updateById(member);

        assertThatThrownBy(() -> service.poll(pairing.requestId(), pairing.deviceCode()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
    }

    private void expire(String requestId) {
        McpAuthRequestEntity entity = mapper.selectOne(Wrappers.<McpAuthRequestEntity>lambdaQuery()
                .eq(McpAuthRequestEntity::getRequestId, requestId));
        entity.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        mapper.updateById(entity);
    }
}

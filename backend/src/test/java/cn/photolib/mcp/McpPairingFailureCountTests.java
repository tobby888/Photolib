package cn.photolib.mcp;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.mcp.mapper.McpAuthRequestMapper;
import cn.photolib.mcp.model.McpAuthRequestEntity;
import cn.photolib.mcp.model.McpAuthStatus;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 猜错配对码的计数**真的落了库**。
 *
 * <p>这个类刻意不带 {@code @Transactional}：那正是被测的东西。计数是在抛异常之前写下的，
 * 如果 {@code approve} 挂着事务，异常会把它一起回滚掉，上限就形同虚设——而在一个包在
 * 测试事务里的用例里，回滚发生在测试结束之后，中途读到的仍是"已经加过一次"的值，
 * 于是那种写法的测试即使在有缺陷的实现上也会通过（{@link McpAuthorizationServiceTests}
 * 里的同名用例就是这样，它验的是逻辑，不是持久性）。
 *
 * <p>代价是数据要自己收拾：H2 上下文是所有测试共用的，留下的行会跟着跑到别的用例里去。
 */
@SpringBootTest
class McpPairingFailureCountTests {
    @Autowired private McpAuthorizationService service;
    @Autowired private McpAuthRequestMapper mapper;

    private String requestId;

    @AfterEach
    void cleanUp() {
        if (requestId != null) {
            mapper.delete(Wrappers.<McpAuthRequestEntity>lambdaQuery()
                    .eq(McpAuthRequestEntity::getRequestId, requestId));
            requestId = null;
        }
    }

    @Test
    void wrongUserCode_isStillCountedAfterTheRejectionUnwinds() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", "本机", "127.0.0.1");
        requestId = pairing.requestId();
        AuthenticatedUser approver = new AuthenticatedUser(
                1L, "someone", "某成员", UserRole.MINISTER, null, false);

        assertThatThrownBy(() -> service.approve(requestId, "AAAA-AAAA", approver))
                .isInstanceOf(BusinessException.class);

        assertThat(service.describe(requestId).remainingAttempts())
                .isEqualTo(McpAuthorizationService.MAX_FAILED_ATTEMPTS - 1);
    }

    @Test
    void repeatedWrongCodes_reachTheLimitAndKillTheRequest() {
        McpAuthorizationService.Pairing pairing = service.open("Claude Code", null, "127.0.0.1");
        requestId = pairing.requestId();
        AuthenticatedUser approver = new AuthenticatedUser(
                1L, "someone", "某成员", UserRole.MINISTER, null, false);

        for (int attempt = 0; attempt < McpAuthorizationService.MAX_FAILED_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> service.approve(requestId, "AAAA-AAAA", approver))
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(service.describe(requestId).status()).isEqualTo(McpAuthStatus.DENIED);
    }
}

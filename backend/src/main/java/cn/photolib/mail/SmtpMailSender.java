package cn.photolib.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 发信入口：按顺序尝试所有可用的发信邮箱，前一个失败就顺延到下一个。
 *
 * <p>顺序是后台「邮件模板 → 发信邮箱」里启用的邮箱（{@code smtp_account.sort_order}），最后是
 * {@code .env} 里的 {@code SMTP_*}（配齐了才有）。每次发信都现查一遍，管理员改完下一封就生效，不用重启。</p>
 *
 * <p>切换原则以「是否投递成功」为准：只要前序邮箱投递失败（登录失败、连不上、超时、限频、
 * 发件人或收件人被该服务商拒收、密码无法解密等），就立即顺延到下一个邮箱继续投递，
 * 直到某一邮箱投递成功或全部邮箱尝试完毕。只有在本地连邮件内容/收件人语法都无法构造时才不顺延，
 * 见 {@link MailDeliveryException#fallbackAllowed()}。注意读超时可能发生在服务器已经收下邮件之后，
 * 这时顺延有极小概率让收件人收到两封；比起一封都收不到，这个代价可以接受。</p>
 *
 * <p>每个后台邮箱的最近一次成功 / 失败会记回 {@code smtp_account}，管理员在列表里就能看到哪个邮箱坏了。</p>
 */
@Slf4j
@Component
@EnableConfigurationProperties(SmtpProperties.class)
public class SmtpMailSender implements MailSender {
    static final String NOT_CONFIGURED = "邮件服务未配置：请在「系统管理 → 邮件模板 → 发信邮箱」添加 SMTP 邮箱，"
            + "或在服务器 .env 中填写 SMTP_HOST、SMTP_USERNAME 和 SMTP_PASSWORD";

    private final SmtpProperties properties;
    private final SmtpAccountService accounts;

    public SmtpMailSender(SmtpProperties properties, SmtpAccountService accounts) {
        this.properties = properties;
        this.accounts = accounts;
    }

    @Override
    public boolean configured() {
        if (properties.configured()) return true;
        try {
            return accounts.enabledCount() > 0;
        } catch (DataAccessException ex) {
            log.warn("读取发信邮箱失败：{}", ex.getMessage());
            return false;
        }
    }

    @Override
    public String fromAddress() {
        List<SmtpServer> routes = routes();
        return routes.isEmpty() ? null : routes.getFirst().sender();
    }

    @Override
    public void send(OutgoingMail mail) {
        List<SmtpServer> routes = routes();
        if (routes.isEmpty()) throw new MailDeliveryException(NOT_CONFIGURED);
        List<Failure> failures = new ArrayList<>();
        MailDeliveryException last = null;
        for (int i = 0; i < routes.size(); i++) {
            SmtpServer server = routes.get(i);
            try {
                SmtpTransport.send(server, properties, mail);
                accounts.recordSuccess(server.id());
                if (i > 0) log.warn("前 {} 个发信邮箱发送失败，已改用{}发出", i, server.label());
                return;
            } catch (MailDeliveryException ex) {
                last = ex;
                failures.add(new Failure(server.label(), ex.getMessage()));
                if (!ex.fallbackAllowed()) {
                    // 收件人或内容的问题：不算这个邮箱的错，也不再试后面的邮箱。
                    throw new MailDeliveryException(summary(routes.size(), failures, false), ex, false);
                }
                accounts.recordFailure(server.id(), ex.getMessage());
                log.warn("发信邮箱{}发送失败（第 {}/{} 个）：{}", server.label(), i + 1, routes.size(), ex.getMessage());
            }
        }
        throw new MailDeliveryException(summary(routes.size(), failures, true), last, true);
    }

    /** 只用指定的后台邮箱发（测试这个邮箱能不能用），不顺延。返回实际的发件地址。 */
    public String sendVia(long accountId, OutgoingMail mail) {
        return accounts.sendVia(accountId, mail);
    }

    /** 这次发信会依次尝试的邮箱。 */
    List<SmtpServer> routes() {
        List<SmtpServer> routes = new ArrayList<>();
        try {
            routes.addAll(accounts.enabledServers());
        } catch (DataAccessException ex) {
            // 数据库抖一下时 .env 的兜底邮箱照样能发。
            log.warn("读取发信邮箱失败，只用 .env 配置发信：{}", ex.getMessage());
        }
        SmtpServer env = properties.envServer();
        if (env != null) routes.add(env);
        return routes;
    }

    /**
     * 只有一个邮箱时原样返回它的原因（和以前一样）；多个时逐个列出，管理员能看出是哪几个坏了。
     */
    static String summary(int total, List<Failure> failures, boolean exhausted) {
        if (total == 1 && failures.size() == 1) return failures.getFirst().message();
        StringBuilder text = new StringBuilder(exhausted
                ? "全部 " + total + " 个发信邮箱都没能发出：" : "邮件未发出：");
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) text.append("；");
            Failure failure = failures.get(i);
            text.append(SmtpAccountService.clip(failure.label() + failure.message(), 240));
        }
        return SmtpAccountService.clip(text.toString(), 900);
    }

    record Failure(String label, String message) {}
}

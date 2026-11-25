package cn.photolib.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端验证多 SMTP 邮箱的真实协议交互（含登录名与发件地址分离、前序失败顺延、收件人拒收不顺延、
 * 发件人被拒顺延、停用跳过、顺序调整以及 .env 兜底）。
 */
@SpringBootTest
@Transactional
class SmtpMailSenderTests {

    @Autowired private JdbcClient jdbc;

    private SmtpProperties baseProps;
    private SmtpAccountService accounts;
    private SmtpMailSender sender;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM smtp_account").update();
        baseProps = new SmtpProperties(null, 465, null, null, null, null,
                false, false, Duration.ofSeconds(3), Duration.ofSeconds(5), 2, Duration.ZERO, null, null);
        accounts = new SmtpAccountService(jdbc, baseProps);
        sender = new SmtpMailSender(baseProps, accounts);
    }

    @Test
    void supportsSeparateSmtpUsernameAndFromAddress() throws Exception {
        try (FakeSmtpServer smtp = FakeSmtpServer.start(FakeSmtpServer.Mode.ACCEPT)) {
            accounts.create(new SmtpAccountService.Input(
                    "SendGrid 通道", "127.0.0.1", smtp.port(),
                    "apikey", "SG.fake-secret", "noreply@studio.cn", "摄影部通知", SmtpServer.NONE, true));

            assertThat(sender.configured()).isTrue();
            assertThat(sender.fromAddress()).isEqualTo("noreply@studio.cn");

            sender.send(sampleMail("member@example.cn"));

            assertThat(smtp.received()).hasSize(1);
            FakeSmtpServer.Captured captured = smtp.received().getFirst();
            assertThat(captured.authUsername()).isEqualTo("apikey");
            assertThat(captured.mailFrom()).contains("noreply@studio.cn");
            assertThat(captured.rcptTo()).contains("member@example.cn");
            assertThat(captured.rawData()).contains("noreply@studio.cn");
            assertThat(captured.rawData()).contains("cid:brand-logo");
        }
    }

    @Test
    void fallsBackSequentiallyWhenEarlierMailboxesFail() throws Exception {
        try (FakeSmtpServer authFail = FakeSmtpServer.start(FakeSmtpServer.Mode.REJECT_AUTH);
             FakeSmtpServer rateLimited = FakeSmtpServer.start(FakeSmtpServer.Mode.REJECT_DATA_451);
             FakeSmtpServer healthy = FakeSmtpServer.start(FakeSmtpServer.Mode.ACCEPT)) {

            var first = accounts.create(new SmtpAccountService.Input(
                    "主邮箱", "127.0.0.1", authFail.port(),
                    "first@studio.cn", "bad-pass", null, null, SmtpServer.NONE, true));
            var disabled = accounts.create(new SmtpAccountService.Input(
                    "停用邮箱", "127.0.0.1", healthy.port(),
                    "disabled@studio.cn", "pass", null, null, SmtpServer.NONE, false));
            var second = accounts.create(new SmtpAccountService.Input(
                    "备用一", "127.0.0.1", rateLimited.port(),
                    "second@studio.cn", "pass", null, null, SmtpServer.NONE, true));
            var third = accounts.create(new SmtpAccountService.Input(
                    "备用二", "127.0.0.1", healthy.port(),
                    "third@studio.cn", "pass", null, null, SmtpServer.NONE, true));

            sender.send(sampleMail("member@example.cn"));

            assertThat(healthy.received()).hasSize(1);
            assertThat(healthy.received().getFirst().mailFrom()).contains("third@studio.cn");

            var overview = accounts.overview().accounts();
            var firstView = overview.stream().filter(a -> a.id() == first.id()).findFirst().orElseThrow();
            var disabledView = overview.stream().filter(a -> a.id() == disabled.id()).findFirst().orElseThrow();
            var secondView = overview.stream().filter(a -> a.id() == second.id()).findFirst().orElseThrow();
            var thirdView = overview.stream().filter(a -> a.id() == third.id()).findFirst().orElseThrow();

            assertThat(firstView.lastFailureAt()).isNotNull();
            assertThat(firstView.lastError()).contains("登录失败");
            assertThat(disabledView.lastSuccessAt()).isNull();
            assertThat(disabledView.lastFailureAt()).isNull();
            assertThat(secondView.lastFailureAt()).isNotNull();
            assertThat(secondView.lastError()).contains("451");
            assertThat(thirdView.lastSuccessAt()).isNotNull();
            assertThat(thirdView.lastFailureAt()).isNull();

            // 停用邮箱依然可以通过 sendVia 单独测试。
            String usedSender = sender.sendVia(disabled.id(), sampleMail("admin@example.cn"));
            assertThat(usedSender).isEqualTo("disabled@studio.cn");
            assertThat(healthy.received()).hasSize(2);
        }
    }

    @Test
    void fallsBackWhenSenderAddressRejectedByFirstServerButStopsOnInvalidRecipient() throws Exception {
        try (FakeSmtpServer rejectSender = FakeSmtpServer.start(FakeSmtpServer.Mode.REJECT_MAIL_FROM);
             FakeSmtpServer rejectRecipient = FakeSmtpServer.start(FakeSmtpServer.Mode.REJECT_RCPT_550);
             FakeSmtpServer healthy = FakeSmtpServer.start(FakeSmtpServer.Mode.ACCEPT)) {

            // 1) MAIL FROM 被拒属于发信通道问题，应当顺延到下一个邮箱。
            var senderRejectedAcc = accounts.create(new SmtpAccountService.Input(
                    "未验证发件域", "127.0.0.1", rejectSender.port(),
                    "relay-user", "pass", "unverified@studio.cn", null, SmtpServer.NONE, true));
            var backupAcc = accounts.create(new SmtpAccountService.Input(
                    "正常备用", "127.0.0.1", healthy.port(),
                    "ok@studio.cn", "pass", null, null, SmtpServer.NONE, true));

            sender.send(sampleMail("member@example.cn"));
            assertThat(healthy.received()).hasSize(1);
            assertThat(healthy.received().getFirst().mailFrom()).contains("ok@studio.cn");

            // 2) RCPT TO 550 属于收件人无效，不应顺延，且不把发信邮箱记为故障。
            accounts.delete(senderRejectedAcc.id());
            var rcptRejectedAcc = accounts.create(new SmtpAccountService.Input(
                    "先试邮箱", "127.0.0.1", rejectRecipient.port(),
                    "primary@studio.cn", "pass", null, null, SmtpServer.NONE, true));
            accounts.reorder(List.of(rcptRejectedAcc.id(), backupAcc.id()));

            assertThatThrownBy(() -> sender.send(sampleMail("nonexistent@example.cn")))
                    .isInstanceOf(MailDeliveryException.class)
                    .hasMessageContaining("收件地址被邮件服务器拒收");

            // 备用邮箱没有被多余调用。
            assertThat(healthy.received()).hasSize(1);
            var primaryView = accounts.overview().accounts().stream()
                    .filter(a -> a.id() == rcptRejectedAcc.id()).findFirst().orElseThrow();
            assertThat(primaryView.lastFailureAt()).isNull();
        }
    }

    @Test
    void mergesAllErrorsWhenEveryMailboxFailsAndUsesEnvAsLastFallback() throws Exception {
        try (FakeSmtpServer fail1 = FakeSmtpServer.start(FakeSmtpServer.Mode.REJECT_AUTH);
             FakeSmtpServer fail2 = FakeSmtpServer.start(FakeSmtpServer.Mode.REJECT_DATA_451);
             FakeSmtpServer envHealthy = FakeSmtpServer.start(FakeSmtpServer.Mode.ACCEPT)) {

            accounts.create(new SmtpAccountService.Input(
                    "邮箱A", "127.0.0.1", fail1.port(),
                    "a@studio.cn", "bad", null, null, SmtpServer.NONE, true));
            accounts.create(new SmtpAccountService.Input(
                    "邮箱B", "127.0.0.1", fail2.port(),
                    "b@studio.cn", "bad", null, null, SmtpServer.NONE, true));

            // 无 .env 兜底时全部失败并合并每个邮箱的原因。
            assertThatThrownBy(() -> sender.send(sampleMail("member@example.cn")))
                    .isInstanceOf(MailDeliveryException.class)
                    .hasMessageContaining("全部 2 个发信邮箱都没能发出")
                    .hasMessageContaining("「邮箱A」")
                    .hasMessageContaining("「邮箱B」");

            // 配置 .env 兜底后，前两个失败会顺延到 .env（支持独立 SMTP_FROM_ADDRESS）。
            SmtpProperties withEnv = new SmtpProperties("127.0.0.1", envHealthy.port(), "env-login", "env-pass",
                    "fallback@studio.cn", "兜底发件人", false, false,
                    Duration.ofSeconds(3), Duration.ofSeconds(5), 2, Duration.ZERO, null, null);
            SmtpAccountService accountsWithEnv = new SmtpAccountService(jdbc, withEnv);
            SmtpMailSender senderWithEnv = new SmtpMailSender(withEnv, accountsWithEnv);

            senderWithEnv.send(sampleMail("member@example.cn"));
            assertThat(envHealthy.received()).hasSize(1);
            assertThat(envHealthy.received().getFirst().authUsername()).isEqualTo("env-login");
            assertThat(envHealthy.received().getFirst().mailFrom()).contains("fallback@studio.cn");
        }
    }

    @Test
    void skipsUnreadableEncryptedPasswordAndFallsBackToNextAccount() throws Exception {
        String oldKey = Base64.getEncoder().encodeToString("1111111111111111".getBytes());
        String newKey = Base64.getEncoder().encodeToString("2222222222222222".getBytes());
        try (FakeSmtpServer healthy = FakeSmtpServer.start(FakeSmtpServer.Mode.ACCEPT)) {
            SmtpProperties oldProps = new SmtpProperties(null, 465, null, null, null, null,
                    false, false, Duration.ofSeconds(3), Duration.ofSeconds(5), 2, Duration.ZERO, null, oldKey);
            new SmtpAccountService(jdbc, oldProps).create(new SmtpAccountService.Input(
                    "旧密钥邮箱", "127.0.0.1", healthy.port(),
                    "old@studio.cn", "secret", null, null, SmtpServer.NONE, true));

            SmtpProperties newProps = new SmtpProperties(null, 465, null, null, null, null,
                    false, false, Duration.ofSeconds(3), Duration.ofSeconds(5), 2, Duration.ZERO, null, newKey);
            SmtpAccountService rotatedAccounts = new SmtpAccountService(jdbc, newProps);
            rotatedAccounts.create(new SmtpAccountService.Input(
                    "新密钥邮箱", "127.0.0.1", healthy.port(),
                    "new@studio.cn", "secret", null, null, SmtpServer.NONE, true));
            SmtpMailSender rotatedSender = new SmtpMailSender(newProps, rotatedAccounts);

            rotatedSender.send(sampleMail("member@example.cn"));
            assertThat(healthy.received()).hasSize(1);
            assertThat(healthy.received().getFirst().mailFrom()).contains("new@studio.cn");
        }
    }

    private static OutgoingMail sampleMail(String to) {
        return new OutgoingMail(to, "测试成员", "PhotoLib", "测试主题",
                "<p>正文 <img src=\"cid:brand-logo\"/></p>", "正文",
                List.of(new OutgoingMail.InlineImage("brand-logo", new byte[]{1, 2, 3, 4}, "image/png")));
    }

    /** 最小 ESMTP 测试服务端，覆盖 AUTH PLAIN/LOGIN、MAIL FROM、RCPT TO、DATA。 */
    static final class FakeSmtpServer implements AutoCloseable {
        enum Mode { ACCEPT, REJECT_AUTH, REJECT_MAIL_FROM, REJECT_RCPT_550, REJECT_DATA_451 }

        record Captured(String authUsername, String mailFrom, String rcptTo, String rawData) {}

        private final ServerSocket socket;
        private final Mode mode;
        private final List<Captured> received = new CopyOnWriteArrayList<>();
        private final Thread worker;
        private volatile boolean running = true;

        private FakeSmtpServer(ServerSocket socket, Mode mode) {
            this.socket = socket;
            this.mode = mode;
            this.worker = new Thread(this::acceptLoop, "fake-smtp-" + socket.getLocalPort());
            this.worker.setDaemon(true);
            this.worker.start();
        }

        static FakeSmtpServer start(Mode mode) throws IOException {
            ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            return new FakeSmtpServer(server, mode);
        }

        int port() {
            return socket.getLocalPort();
        }

        List<Captured> received() {
            return Collections.unmodifiableList(new ArrayList<>(received));
        }

        private void acceptLoop() {
            while (running) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (IOException ignored) {
                    if (!running) return;
                }
            }
        }

        private void handle(Socket client) throws IOException {
            client.setSoTimeout(15_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
            send(out, "220 fake.smtp ESMTP");
            String authUser = null;
            String mailFrom = null;
            String rcptTo = null;
            String line;
            while ((line = in.readLine()) != null) {
                String upper = line.toUpperCase();
                if (upper.startsWith("EHLO")) {
                    send(out, "250-fake.smtp");
                    send(out, "250 AUTH PLAIN LOGIN");
                } else if (upper.startsWith("HELO")) {
                    send(out, "250 fake.smtp");
                } else if (upper.startsWith("AUTH PLAIN")) {
                    String payload = line.length() > 11 ? line.substring(11).trim() : null;
                    if (payload == null || payload.isEmpty()) {
                        send(out, "334 ");
                        payload = in.readLine();
                    }
                    authUser = decodePlainUser(payload);
                    if (mode == Mode.REJECT_AUTH) {
                        send(out, "535 5.7.8 Authentication credentials invalid");
                    } else {
                        send(out, "235 2.7.0 Authentication successful");
                    }
                } else if (upper.startsWith("AUTH LOGIN")) {
                    send(out, "334 VXNlcm5hbWU6");
                    String userB64 = in.readLine();
                    authUser = userB64 == null ? null : new String(Base64.getDecoder().decode(userB64), StandardCharsets.UTF_8);
                    send(out, "334 UGFzc3dvcmQ6");
                    in.readLine();
                    if (mode == Mode.REJECT_AUTH) {
                        send(out, "535 5.7.8 Authentication credentials invalid");
                    } else {
                        send(out, "235 2.7.0 Authentication successful");
                    }
                } else if (upper.startsWith("MAIL FROM:")) {
                    mailFrom = line.substring(10).trim();
                    if (mode == Mode.REJECT_MAIL_FROM) {
                        send(out, "553 5.7.1 Sender address not verified");
                    } else {
                        send(out, "250 2.1.0 Ok");
                    }
                } else if (upper.startsWith("RCPT TO:")) {
                    rcptTo = line.substring(8).trim();
                    if (mode == Mode.REJECT_RCPT_550) {
                        send(out, "550 5.1.1 No such user");
                    } else {
                        send(out, "250 2.1.5 Ok");
                    }
                } else if (upper.equals("DATA")) {
                    send(out, "354 End data with <CR><LF>.<CR><LF>");
                    StringBuilder body = new StringBuilder();
                    String dataLine;
                    while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                        body.append(dataLine).append('\n');
                    }
                    if (mode == Mode.REJECT_DATA_451) {
                        send(out, "451 4.7.0 Rate limit exceeded");
                    } else {
                        received.add(new Captured(authUser, mailFrom, rcptTo, body.toString()));
                        send(out, "250 2.0.0 Ok");
                    }
                } else if (upper.equals("RSET")) {
                    mailFrom = null;
                    rcptTo = null;
                    send(out, "250 Ok");
                } else if (upper.equals("QUIT")) {
                    send(out, "221 Bye");
                    return;
                } else {
                    send(out, "250 Ok");
                }
            }
        }

        private static String decodePlainUser(String b64) {
            if (b64 == null) return null;
            String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\0");
            return parts.length >= 2 ? parts[1] : decoded;
        }

        private static void send(BufferedWriter out, String line) throws IOException {
            out.write(line);
            out.write("\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            running = false;
            socket.close();
        }
    }
}

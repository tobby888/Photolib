package cn.photolib.settlement;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.mail.MailDeliveryException;
import cn.photolib.mail.MailSender;
import cn.photolib.mail.OutgoingMail;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 批量发送清算单：一人一封、失败单独记、全站同一时刻只有一个批次、重启后接着发。
 *
 * <p>发信在后台线程里跑，要看得见已提交的数据，所以这个类不用事务回滚，造的数据在
 * {@link #cleanUp()} 里删干净。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {"photolib.smtp.send-interval=0s", "photolib.smtp.concurrency=3"})
class SettlementMailServiceTests {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    SettlementMailService service;
    @MockitoBean
    MailSender mailSender;

    private SettlementFixtures fixtures;
    private AuthenticatedUser minister;
    private final List<OutgoingMail> sent = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        fixtures = new SettlementFixtures(jdbc);
        minister = new AuthenticatedUser(fixtures.ownerId, "settle-minister", "王部长", UserRole.MINISTER, null,
                false, 2L, "MINISTER", "部长", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());
        when(mailSender.configured()).thenReturn(true);
        when(mailSender.fromAddress()).thenReturn("bot@example.cn");
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(mailSender).send(any());
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanup();
    }

    @Test
    void sendsOnePersonalStatementPerRecipient() {
        String a = "清算甲" + fixtures.base;
        String b = "清算乙" + fixtures.base;
        long userA = fixtures.user(a, "jia@example.cn");
        long userB = fixtures.user(b, "yi@example.cn");
        fixtures.worklog(a, LocalDate.of(2026, 10, 3), 60, 0, "REJECTED", "照片没交齐");
        fixtures.worklog(b, LocalDate.of(2026, 10, 4), 30, 30, "CONFIRMED", null);
        fixtures.adoption(b, LocalDateTime.of(2026, 10, 20, 10, 0));

        SettlementMailService.BatchView created = service.create(FROM, TO, List.of(userA, userB), minister);
        SettlementMailService.BatchView done = awaitFinished(created.id());

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.sent()).isEqualTo(2);
        assertThat(done.failed()).isZero();
        assertThat(done.deliveries()).allSatisfy(d -> assertThat(d.status()).isEqualTo("SENT"));
        assertThat(sent).hasSize(2);
        OutgoingMail toA = sent.stream().filter(m -> m.to().equals("jia@example.cn")).findFirst().orElseThrow();
        assertThat(toA.toName()).isEqualTo(a);
        assertThat(toA.subject()).contains(a).contains("2026-10-01").contains("2026-10-31");
        assertThat(toA.html()).contains("照片没交齐").doesNotContain(b);
        assertThat(toA.inlineImages()).extracting(OutgoingMail.InlineImage::contentId).containsExactly("brand-logo");
        OutgoingMail toB = sent.stream().filter(m -> m.to().equals("yi@example.cn")).findFirst().orElseThrow();
        assertThat(toB.html()).contains("清算图片").doesNotContain("照片没交齐");
        assertThat(service.status().activeBatchId()).isNull();
    }

    @Test
    void oneFailedMailDoesNotStopTheOthers() {
        long ok = fixtures.user("清算丙" + fixtures.base, "bing@example.cn");
        long bad = fixtures.user("清算丁" + fixtures.base, "ding@example.cn");
        doAnswer(invocation -> {
            OutgoingMail mail = invocation.getArgument(0);
            if (mail.to().startsWith("ding")) throw new MailDeliveryException("收件地址被邮件服务器拒收：550 no such user");
            sent.add(mail);
            return null;
        }).when(mailSender).send(any());

        SettlementMailService.BatchView done = awaitFinished(service.create(FROM, TO, List.of(ok, bad), minister).id());

        assertThat(done.sent()).isEqualTo(1);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.deliveries()).filteredOn(d -> d.userId() == bad).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("FAILED");
            assertThat(d.errorMessage()).contains("550 no such user");
        });
    }

    @Test
    void refusesRecipientsThatCannotReceiveAStatement() {
        String shared = "清算同名" + fixtures.base;
        long first = fixtures.user(shared, "s1@example.cn");
        fixtures.user(shared, "s2@example.cn");
        long noEmail = fixtures.user("清算无邮箱" + fixtures.base, null);

        assertThatThrownBy(() -> service.create(FROM, TO, List.of(first), minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("同名");
        assertThatThrownBy(() -> service.create(FROM, TO, List.of(noEmail), minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有填写邮箱");
        AuthenticatedUser campusScoped = new AuthenticatedUser(fixtures.ownerId, "settle-cm", "负责人",
                UserRole.CAMPUS_MANAGER, fixtures.campusId, false, 3L, "CAMPUS_MANAGER", "校区负责人",
                DataScope.CAMPUS, EnumSet.allOf(PermissionCode.class), Set.of(fixtures.campusId));
        long fine = fixtures.user("清算戊" + fixtures.base, "wu@example.cn");
        assertThatThrownBy(() -> service.create(FROM, TO, List.of(fine), campusScoped))
                .isInstanceOf(BusinessException.class).hasMessageContaining("校区范围");
        when(mailSender.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.create(FROM, TO, List.of(fine), minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("SMTP_HOST");
        assertThat(sent).isEmpty();
    }

    @Test
    void onlyOneBatchRunsAtATimeEvenWhenTwoPeopleClickSendTogether() throws Exception {
        long a = fixtures.user("清算己" + fixtures.base, "ji@example.cn");
        long b = fixtures.user("清算庚" + fixtures.base, "geng@example.cn");
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            sends.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any());

        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService clickers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (long userId : new long[]{a, b}) {
                results.add(clickers.submit(() -> {
                    together.await(5, TimeUnit.SECONDS);
                    try {
                        return service.create(FROM, TO, List.of(userId), minister);
                    } catch (BusinessException ex) {
                        return ex;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(SettlementMailService.BatchView.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(BusinessException.class::isInstance).singleElement()
                    .satisfies(error -> {
                        assertThat(((BusinessException) error).getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
                        assertThat(((BusinessException) error).getMessage()).contains("王部长").contains("正在发送");
                    });
            SettlementMailService.BatchView winner = (SettlementMailService.BatchView) outcomes.stream()
                    .filter(SettlementMailService.BatchView.class::isInstance).findFirst().orElseThrow();
            assertThat(service.status().activeBatchId()).isEqualTo(winner.id());

            release.countDown();
            awaitFinished(winner.id());
            assertThat(sends.get()).isEqualTo(1);
            // 前一批发完、发送位释放后，下一批可以正常发。
            SettlementMailService.BatchView next = service.create(FROM, TO, List.of(a, b), minister);
            assertThat(awaitFinished(next.id()).sent()).isEqualTo(2);
        } finally {
            release.countDown();
            clickers.shutdownNow();
        }
    }

    @Test
    void resumesAnInterruptedBatchWithoutResendingUncertainMails() {
        long sending = fixtures.user("清算辛" + fixtures.base, "xin@example.cn");
        long pending = fixtures.user("清算壬" + fixtures.base, "ren@example.cn");
        jdbc.sql("""
                INSERT INTO settlement_mail_batch(status, active_slot, from_date, to_date, created_by, created_by_name,
                                                  total_count, sent_count, failed_count)
                VALUES ('RUNNING', 1, :fromDate, :toDate, :userId, '王部长', 2, 0, 0)
                """).param("fromDate", FROM).param("toDate", TO).param("userId", fixtures.ownerId).update();
        long batchId = jdbc.sql("SELECT id FROM settlement_mail_batch WHERE active_slot=1").query(Long.class).single();
        for (long userId : new long[]{sending, pending}) {
            jdbc.sql("""
                    INSERT INTO settlement_mail_delivery(batch_id, user_id, recipient_name, recipient_email, status)
                    SELECT :batchId, id, display_name, email, :status FROM app_user WHERE id=:userId
                    """).param("batchId", batchId).param("userId", userId)
                    .param("status", userId == sending ? "SENDING" : "PENDING").update();
        }

        service.resumeInterrupted();
        SettlementMailService.BatchView done = awaitFinished(batchId);

        assertThat(done.sent()).isEqualTo(1);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.deliveries()).filteredOn(d -> d.userId() == sending).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("FAILED");
            assertThat(d.errorMessage()).contains("重启");
        });
        assertThat(sent).extracting(OutgoingMail::to).containsExactly("ren@example.cn");
    }

    private SettlementMailService.BatchView awaitFinished(long batchId) {
        long deadline = System.currentTimeMillis() + 15_000;
        SettlementMailService.BatchView view = service.batch(batchId, minister);
        while (!"COMPLETED".equals(view.status()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            view = service.batch(batchId, minister);
        }
        assertThat(view.status()).as("批次应在 15 秒内发完").isEqualTo("COMPLETED");
        return view;
    }
}

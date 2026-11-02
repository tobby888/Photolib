package cn.photolib.settlement;

import cn.photolib.admin.BrandSnapshotService;
import cn.photolib.admin.BrandSnapshotService.BrandSnapshot;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.mail.MailDeliveryException;
import cn.photolib.mail.MailSender;
import cn.photolib.mail.OutgoingMail;
import cn.photolib.mail.SmtpProperties;
import cn.photolib.settlement.SettlementStatementService.Recipient;
import cn.photolib.settlement.SettlementStatementService.Statement;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量发送邮件清算单。
 *
 * <p><b>并发控制</b>，从外到内三层：</p>
 * <ol>
 *   <li><b>全站同一时刻只有一个批次在发。</b>批次行的 {@code active_slot} 在进行中为 1、结束置 NULL，
 *       上面有唯一索引。两个人同时点「发送」，后插入的那个撞唯一约束、直接被拒，不会给同一批人各发一遍；
 *       这道闸在数据库里，多开几个实例也成立。</li>
 *   <li><b>每封信先「认领」再发。</b>投递行从 PENDING 改成 SENDING 用带状态条件的 UPDATE，
 *       只有改到 1 行的线程才发。发完从 SENDING 改成 SENT / FAILED 同样带条件，计数用
 *       {@code sent_count = sent_count + 1} 在库里累加，几个发信线程不会互相覆盖进度。</li>
 *   <li><b>对 SMTP 服务器限流。</b>同时发信的连接数是 {@code SMTP_CONCURRENCY}（默认 2），每个连接两封信
 *       之间至少隔 {@code SMTP_SEND_INTERVAL}。邮箱服务商对单账号的并发和频率都有限制，超了会临时封号，
 *       那样后面的信全部失败。</li>
 * </ol>
 *
 * <p>进程重启时还在 RUNNING 的批次会接着发：SENDING 的那几封不知道发没发出去，记成失败并写明原因
 * （宁可让人手动重发，也不重复发），PENDING 的照常发完。</p>
 */
@Slf4j
@Service
public class SettlementMailService {
    static final int MAX_RECIPIENTS = 500;
    private static final int ACTIVE = 1;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final SettlementStatementService statements;
    private final MailTemplateService templates;
    private final BrandSnapshotService brands;
    private final MailSender mailSender;
    private final SmtpProperties smtp;
    private final ExecutorService workers;

    public SettlementMailService(JdbcClient jdbc, TransactionTemplate transactions,
                                 SettlementStatementService statements, MailTemplateService templates,
                                 BrandSnapshotService brands, MailSender mailSender, SmtpProperties smtp) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.statements = statements;
        this.templates = templates;
        this.brands = brands;
        this.mailSender = mailSender;
        this.smtp = smtp;
        AtomicInteger counter = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(smtp.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "settlement-mail-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    public Status status() {
        Long active = jdbc.sql("SELECT id FROM settlement_mail_batch WHERE active_slot=:slot")
                .param("slot", ACTIVE).query(Long.class).optional().orElse(null);
        return new Status(mailSender.configured(), mailSender.fromAddress(), active, siteUrl() != null);
    }

    public List<Recipient> recipients(LocalDate from, LocalDate to, AuthenticatedUser principal) {
        requireGlobal(principal);
        return statements.recipients(from, to);
    }

    /** 预览某个人的清算单；{@code subject}/{@code html} 非空时用它们代替当前模板（模板编辑器的实时预览）。 */
    public SettlementMailRenderer.Rendered preview(long userId, LocalDate from, LocalDate to, String subject,
                                                   String html, AuthenticatedUser principal) {
        requireGlobal(principal);
        Recipient recipient = statements.recipient(userId, from, to);
        MailTemplateService.Template template = templates.current();
        return SettlementMailRenderer.render(subject != null ? subject : template.subject(),
                html != null ? html : template.html(), statements.statement(recipient, from, to),
                brands.current(), principal.displayName(), siteUrl(), LocalDateTime.now(), true);
    }

    /** 管理员调模板时发一封给自己：内容是所选成员的清算单，收件人是自己。同步发送，结果直接返回。 */
    public String sendTest(long userId, LocalDate from, LocalDate to, String subject, String html,
                           AuthenticatedUser principal) {
        requireGlobal(principal);
        requireConfigured();
        String ownEmail = jdbc.sql("SELECT email FROM app_user WHERE id=:id AND deleted=0").param("id", principal.id())
                .query(String.class).optional().orElse(null);
        if (ownEmail == null || ownEmail.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "你的账号没有填写邮箱，收不到测试邮件");
        }
        Recipient recipient = statements.recipient(userId, from, to);
        BrandSnapshot brand = brands.current();
        MailTemplateService.Template template = templates.current();
        SettlementMailRenderer.Rendered rendered = SettlementMailRenderer.render(
                subject != null ? subject : template.subject(), html != null ? html : template.html(),
                statements.statement(recipient, from, to), brand, principal.displayName(), siteUrl(),
                LocalDateTime.now(), false);
        try {
            mailSender.send(new OutgoingMail(ownEmail.trim(), principal.displayName(), brand.title(),
                    "[测试] " + rendered.subject(), rendered.html(), rendered.plainText(), rendered.inlineImages()));
        } catch (MailDeliveryException ex) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, ex.getMessage());
        }
        return ownEmail.trim();
    }

    /**
     * 建一个批次并开始在后台发送，立即返回。收件人必须都能收：账号在用、有邮箱、没有同名账号。
     * 有一个不满足就整批拒绝并列出是谁——前端本来就不让选这些人，到这里说明名单已经过期了。
     */
    public BatchView create(LocalDate from, LocalDate to, List<Long> userIds, AuthenticatedUser principal) {
        requireGlobal(principal);
        requireConfigured();
        SettlementStatementService.validateRange(from, to);
        LinkedHashSet<Long> ids = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        ids.remove(null);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择收件人");
        if (ids.size() > MAX_RECIPIENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "一次最多发给 " + MAX_RECIPIENTS + " 人");
        }
        Map<Long, Recipient> candidates = statements.recipients(from, to).stream()
                .collect(Collectors.toMap(Recipient::userId, Function.identity()));
        List<Recipient> selected = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Long id : ids) {
            Recipient recipient = candidates.get(id);
            if (recipient == null) problems.add("账号 #" + id + " 不存在或已停用");
            else if (recipient.email() == null) problems.add(recipient.displayName() + " 没有填写邮箱");
            else if (recipient.sameNameCount() > 0) problems.add(recipient.displayName() + " 与其他账号同名，无法按姓名区分记录");
            else selected.add(recipient);
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "以下收件人不能接收清算单：" + String.join("；",
                    problems.size() > 10 ? problems.subList(0, 10) : problems)
                    + (problems.size() > 10 ? " 等 " + problems.size() + " 人" : ""));
        }
        long batchId = insertBatch(from, to, selected, principal);
        dispatch(batchId);
        return batch(batchId, principal);
    }

    private long insertBatch(LocalDate from, LocalDate to, List<Recipient> recipients, AuthenticatedUser principal) {
        try {
            Long id = transactions.execute(status -> {
                KeyHolder keys = new GeneratedKeyHolder();
                jdbc.sql("""
                        INSERT INTO settlement_mail_batch(status, active_slot, from_date, to_date, created_by,
                            created_by_name, total_count, created_at)
                        VALUES ('RUNNING', :slot, :fromDate, :toDate, :userId, :userName, :total, :now)
                        """).param("slot", ACTIVE).param("fromDate", from).param("toDate", to)
                        .param("userId", principal.id()).param("userName", principal.displayName())
                        .param("total", recipients.size()).param("now", LocalDateTime.now())
                        .update(keys, "id");
                long batchId = keys.getKeyAs(Number.class).longValue();
                for (Recipient recipient : recipients) {
                    jdbc.sql("""
                            INSERT INTO settlement_mail_delivery(batch_id, user_id, recipient_name, recipient_email,
                                status, worklog_count, adoption_count)
                            VALUES (:batchId, :userId, :name, :email, 'PENDING', :worklogs, :adoptions)
                            """).param("batchId", batchId).param("userId", recipient.userId())
                            .param("name", recipient.displayName()).param("email", recipient.email())
                            .param("worklogs", recipient.worklogCount()).param("adoptions", recipient.adoptionCount())
                            .update();
                }
                return batchId;
            });
            return id == null ? 0 : id;
        } catch (DuplicateKeyException | ConcurrencyFailureException ex) {
            // MySQL 上后到的插入等前一个提交后报唯一冲突；H2 可能直接报并发更新冲突。两种都是「有人先占了发送位」。
            String owner = jdbc.sql("SELECT created_by_name FROM settlement_mail_batch WHERE active_slot=:slot")
                    .param("slot", ACTIVE).query(String.class).optional().orElse("其他人");
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    owner + " 发起的一批清算单正在发送，请等它发完再发");
        }
    }

    /** 把批次里还没发的信分给发信线程。全部线程结束后收尾、释放全站唯一的发送位。 */
    private void dispatch(long batchId) {
        BatchRow batch = batchRow(batchId);
        if (batch == null) return;
        ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>(jdbc.sql("""
                SELECT id FROM settlement_mail_delivery WHERE batch_id=:batchId AND status='PENDING' ORDER BY id
                """).param("batchId", batchId).query(Long.class).list());
        BatchContext context;
        try {
            MailTemplateService.Template template = templates.current();
            context = new BatchContext(batch, template.subject(), template.html(), brands.current(), siteUrl());
        } catch (RuntimeException ex) {
            log.error("清算单批次 {} 无法开始：读取模板或品牌信息失败", batchId, ex);
            failRemaining(batchId, "读取邮件模板失败：" + ex.getMessage());
            finish(batchId);
            return;
        }
        int threads = Math.max(1, Math.min(smtp.concurrency(), queue.size()));
        CompletableFuture<?>[] running = new CompletableFuture<?>[threads];
        for (int i = 0; i < threads; i++) {
            running[i] = CompletableFuture.runAsync(() -> drain(queue, context), workers);
        }
        CompletableFuture.allOf(running).whenComplete((ignored, error) -> {
            if (error != null) log.error("清算单批次 {} 的发信线程异常结束", batchId, error);
            // 线程池被关掉（停机）时没发的留在 PENDING，下次启动接着发。
            if (!workers.isShutdown()) finish(batchId);
        });
    }

    private void drain(ConcurrentLinkedQueue<Long> queue, BatchContext context) {
        Long deliveryId;
        boolean first = true;
        while ((deliveryId = queue.poll()) != null) {
            if (Thread.currentThread().isInterrupted()) return;
            if (!first && !smtp.sendInterval().isZero()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(smtp.sendInterval().toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            first = false;
            deliver(deliveryId, context);
        }
    }

    private void deliver(long deliveryId, BatchContext context) {
        int claimed = jdbc.sql("""
                UPDATE settlement_mail_delivery SET status='SENDING', updated_at=:now
                WHERE id=:id AND status='PENDING'
                """).param("id", deliveryId).param("now", LocalDateTime.now()).update();
        if (claimed == 0) return;
        DeliveryRow delivery = jdbc.sql("""
                SELECT user_id, recipient_name, recipient_email FROM settlement_mail_delivery WHERE id=:id
                """).param("id", deliveryId)
                .query((rs, n) -> new DeliveryRow(rs.getLong(1), rs.getString(2), rs.getString(3))).single();
        try {
            BatchRow batch = context.batch();
            // 发的时候现取一次：收件人名单里的概况是建批次那一刻的，这期间可能又有工时被审核了。
            Recipient recipient = statements.recipient(delivery.userId(), batch.from(), batch.to());
            if (recipient.sameNameCount() > 0) throw new MailDeliveryException("与其他账号同名，无法按姓名区分记录");
            Statement statement = statements.statement(recipient, batch.from(), batch.to());
            SettlementMailRenderer.Rendered rendered = SettlementMailRenderer.render(context.subject(), context.html(),
                    statement, context.brand(), batch.createdByName(), context.siteUrl(), LocalDateTime.now(), false);
            mailSender.send(new OutgoingMail(delivery.email(), delivery.name(), context.brand().title(),
                    rendered.subject(), rendered.html(), rendered.plainText(), rendered.inlineImages()));
            markDone(deliveryId, batch.id(), true, null);
        } catch (MailDeliveryException | BusinessException ex) {
            markDone(deliveryId, context.batch().id(), false, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("清算单投递 {} 失败", deliveryId, ex);
            markDone(deliveryId, context.batch().id(), false, "生成或发送时出错：" + ex.getMessage());
        }
    }

    private void markDone(long deliveryId, long batchId, boolean sent, String error) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.sql("""
                    UPDATE settlement_mail_delivery SET status=:status, error_message=:error,
                        sent_at=:sentAt, updated_at=:now
                    WHERE id=:id AND status='SENDING'
                    """).param("status", sent ? "SENT" : "FAILED").param("error", clip(error))
                    .param("sentAt", sent ? LocalDateTime.now() : null).param("now", LocalDateTime.now())
                    .param("id", deliveryId).update();
            if (changed == 0) return;
            jdbc.sql(sent
                    ? "UPDATE settlement_mail_batch SET sent_count=sent_count+1 WHERE id=:id"
                    : "UPDATE settlement_mail_batch SET failed_count=failed_count+1 WHERE id=:id")
                    .param("id", batchId).update();
        });
    }

    private void failRemaining(long batchId, String reason) {
        transactions.executeWithoutResult(status -> {
            int failed = jdbc.sql("""
                    UPDATE settlement_mail_delivery SET status='FAILED', error_message=:error, updated_at=:now
                    WHERE batch_id=:batchId AND status IN ('PENDING','SENDING')
                    """).param("error", clip(reason)).param("now", LocalDateTime.now())
                    .param("batchId", batchId).update();
            jdbc.sql("UPDATE settlement_mail_batch SET failed_count=failed_count+:n WHERE id=:id")
                    .param("n", failed).param("id", batchId).update();
        });
    }

    private void finish(long batchId) {
        jdbc.sql("""
                UPDATE settlement_mail_batch SET status='COMPLETED', active_slot=NULL, finished_at=:now
                WHERE id=:id AND status='RUNNING'
                """).param("now", LocalDateTime.now()).param("id", batchId).update();
    }

    /** 启动时接着发上次没发完的批次。 */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        List<Long> running = jdbc.sql("SELECT id FROM settlement_mail_batch WHERE status='RUNNING' ORDER BY id")
                .query(Long.class).list();
        for (Long batchId : running) {
            transactions.executeWithoutResult(status -> {
                int uncertain = jdbc.sql("""
                        UPDATE settlement_mail_delivery SET status='FAILED', updated_at=:now,
                            error_message='服务在发送这封邮件时重启，无法确认是否送达；如对方没收到请重发'
                        WHERE batch_id=:batchId AND status='SENDING'
                        """).param("now", LocalDateTime.now()).param("batchId", batchId).update();
                jdbc.sql("UPDATE settlement_mail_batch SET failed_count=failed_count+:n WHERE id=:id")
                        .param("n", uncertain).param("id", batchId).update();
            });
            if (!mailSender.configured()) {
                failRemaining(batchId, "邮件服务未配置，未发送");
                finish(batchId);
                continue;
            }
            log.info("接着发送重启前未完成的清算单批次 {}", batchId);
            dispatch(batchId);
        }
    }

    public List<BatchView> batches(int limit, AuthenticatedUser principal) {
        requireGlobal(principal);
        int size = Math.max(1, Math.min(50, limit));
        return jdbc.sql("""
                SELECT id, status, from_date, to_date, created_by_name, total_count, sent_count, failed_count,
                       created_at, finished_at
                FROM settlement_mail_batch ORDER BY id DESC LIMIT :limit
                """).param("limit", size)
                .query((rs, n) -> new BatchView(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                        rs.getObject(9, LocalDateTime.class), rs.getObject(10, LocalDateTime.class), null))
                .list();
    }

    public BatchView batch(long id, AuthenticatedUser principal) {
        requireGlobal(principal);
        BatchView batch = jdbc.sql("""
                SELECT id, status, from_date, to_date, created_by_name, total_count, sent_count, failed_count,
                       created_at, finished_at
                FROM settlement_mail_batch WHERE id=:id
                """).param("id", id)
                .query((rs, n) -> new BatchView(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                        rs.getObject(9, LocalDateTime.class), rs.getObject(10, LocalDateTime.class), null))
                .optional().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "发送记录不存在"));
        List<DeliveryView> deliveries = jdbc.sql("""
                SELECT id, user_id, recipient_name, recipient_email, status, worklog_count, adoption_count,
                       error_message, sent_at
                FROM settlement_mail_delivery WHERE batch_id=:id ORDER BY id
                """).param("id", id)
                .query((rs, n) -> new DeliveryView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                        rs.getObject(9, LocalDateTime.class)))
                .list();
        return new BatchView(batch.id(), batch.status(), batch.from(), batch.to(), batch.createdByName(),
                batch.total(), batch.sent(), batch.failed(), batch.createdAt(), batch.finishedAt(), deliveries);
    }

    private BatchRow batchRow(long id) {
        return jdbc.sql("SELECT id, from_date, to_date, created_by_name FROM settlement_mail_batch WHERE id=:id")
                .param("id", id)
                .query((rs, n) -> new BatchRow(rs.getLong(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getString(4)))
                .optional().orElse(null);
    }

    /**
     * 清算单汇总的是全校区的工时和被引，收件人也是全校区的账号。校区范围的账号即使被授予了
     * 这条权限也不能用，否则等于绕过校区边界看别的校区的人的工时。
     */
    static void requireGlobal(AuthenticatedUser principal) {
        if (principal.isCampusScoped()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "清算单汇总全部校区的记录，校区范围的账号不能发送");
        }
    }

    private void requireConfigured() {
        if (!mailSender.configured()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "邮件服务未配置：请在服务器 .env 中填写 SMTP_HOST、SMTP_USERNAME 和 SMTP_PASSWORD 后重启");
        }
    }

    String siteUrl() {
        String base = smtp.siteBaseUrl();
        if (base == null || base.isBlank()) return null;
        String url = base.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url + "/#/";
    }

    private static String clip(String value) {
        if (value == null) return null;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private record BatchRow(long id, LocalDate from, LocalDate to, String createdByName) {}

    private record DeliveryRow(long userId, String name, String email) {}

    private record BatchContext(BatchRow batch, String subject, String html, BrandSnapshot brand, String siteUrl) {}

    /** {@code activeBatchId}：正在发送的批次，没有时为 {@code null}。 */
    public record Status(boolean configured, String fromAddress, Long activeBatchId, boolean siteUrlConfigured) {}

    /** {@code deliveries} 只在查单个批次时有值。 */
    public record BatchView(long id, String status, LocalDate from, LocalDate to, String createdByName, int total,
                            int sent, int failed, LocalDateTime createdAt, LocalDateTime finishedAt,
                            List<DeliveryView> deliveries) {}

    public record DeliveryView(long id, long userId, String recipientName, String recipientEmail, String status,
                               int worklogCount, int adoptionCount, String errorMessage, LocalDateTime sentAt) {}
}

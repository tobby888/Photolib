package cn.photolib.worklog;

import cn.photolib.admin.BrandSnapshotService;
import cn.photolib.mail.MailRendering;
import cn.photolib.mail.MailSender;
import cn.photolib.mail.OutgoingMail;
import cn.photolib.mail.WorklogMailTemplates;
import cn.photolib.mail.WorklogMailTemplates.Details;
import cn.photolib.mail.WorklogMailTemplates.Kind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
class WorklogMailDelivery {
    private final JdbcClient jdbc;
    private final BrandSnapshotService brands;
    private final MailSender sender;
    private final WorklogMailTemplates templates;
    private final Executor executor;

    WorklogMailDelivery(JdbcClient jdbc, BrandSnapshotService brands, MailSender sender, WorklogMailTemplates templates,
                        @Qualifier("applicationTaskExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.brands = brands;
        this.sender = sender;
        this.templates = templates;
        this.executor = executor;
    }

    @TransactionalEventListener
    public void enqueue(Event event) {
        try {
            if (sender.configured()) executor.execute(() -> deliver(event));
        } catch (RejectedExecutionException ex) {
            log.warn("工时邮件未入队：worklogId={}, kind={}", event.worklogId(), event.kind());
        }
    }

    void deliver(Event event) {
        try {
            if (event.memberName() == null || event.memberName().isBlank()) return;
            String name = event.memberName().strip();
            var matches = jdbc.sql("""
                    SELECT TRIM(display_name), email FROM app_user
                    WHERE deleted=0 AND enabled=TRUE AND TRIM(display_name)=:name
                    LIMIT 2
                    """).param("name", name).query((rs, row) -> new Recipient(rs.getString(1), rs.getString(2))).list();
            if (matches.size() != 1 || !name.equals(matches.getFirst().name())) return;
            Recipient recipient = matches.getFirst();
            if (recipient.email() == null || recipient.email().isBlank()) return;

            String projectTitle = jdbc.sql("SELECT " + WorklogProjectSql.title("w", "p")
                            + " FROM worklog w " + WorklogProjectSql.joins()
                            + " WHERE w.id=:id AND w.deleted=0 AND w.member_name=:name")
                    .param("id", event.worklogId()).param("name", event.memberName())
                    .query((rs, row) -> new ProjectTitle(rs.getString(1))).optional().map(ProjectTitle::value).orElse(null);
            var brand = brands.current();
            var details = new Details(recipient.name(), projectTitle, event.workDate(), event.shootingMinutes(),
                    event.retouchingMinutes(), event.reason(), event.submitIp(), event.userAgent(), event.reviewerName());
            var rendered = templates.render(event.kind(), details, brand, false, null, null, null);
            sender.send(new OutgoingMail(recipient.email().strip(), recipient.name(), brand.title(), rendered.subject(),
                    rendered.html(), rendered.plainText(), MailRendering.images(brand)));
        } catch (RuntimeException ex) {
            log.warn("工时邮件发送失败：worklogId={}, kind={}, error={}", event.worklogId(), event.kind(), ex.getClass().getSimpleName());
        }
    }

    record Event(Kind kind, Long worklogId, String memberName, LocalDate workDate, int shootingMinutes,
                 int retouchingMinutes, String reason, String submitIp, String userAgent, String reviewerName) {}

    private record Recipient(String name, String email) {}

    private record ProjectTitle(String value) {}
}

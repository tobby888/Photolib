package cn.photolib.settlement;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 清算单邮件模板。表里没有行就用内置模板（{@code resources/mail/settlement-default.html}），
 * 「恢复默认」就是删掉这一行——内置模板以后改版，没自定义过的站点自动跟上。
 *
 * <p>正文不做 HTML 清洗：只有管理员能改，它要的就是完全控制邮件长相；收件端的邮件客户端
 * 本来就会去掉脚本。管理端预览放在 {@code sandbox} 的 iframe 里，模板里的脚本碰不到站点会话。</p>
 */
@Service
@RequiredArgsConstructor
public class MailTemplateService {
    public static final String SETTLEMENT = "SETTLEMENT";
    static final int MAX_SUBJECT = 200;
    static final int MAX_HTML = 200_000;
    static final String DEFAULT_SUBJECT = "【{{siteName}}】{{recipientName}}的工作清算单（{{periodFrom}} 至 {{periodTo}}）";

    private final JdbcClient jdbc;
    private volatile String defaultHtml;

    public Template current() {
        return stored().orElseGet(() -> new Template(DEFAULT_SUBJECT, defaultHtml(), false, null, null, 0));
    }

    public TemplateView view() {
        return new TemplateView(current(), DEFAULT_SUBJECT, defaultHtml(), SettlementMailRenderer.VARIABLES);
    }

    /**
     * 保存自定义模板。{@code version} 是编辑器打开时看到的版本（没自定义过为 0），
     * 两个管理员同时改时后保存的那个会被拒绝，而不是悄悄覆盖前一个人的修改。
     */
    @Transactional
    public TemplateView save(String subject, String html, int version, AuthenticatedUser user) {
        String cleanSubject = subject == null ? "" : subject.replaceAll("[\\r\\n]+", " ").trim();
        if (cleanSubject.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写邮件主题");
        if (cleanSubject.length() > MAX_SUBJECT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮件主题最多 " + MAX_SUBJECT + " 个字");
        }
        if (html == null || html.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写邮件正文");
        if (html.length() > MAX_HTML) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮件正文太长（上限 20 万字符）");
        LocalDateTime now = LocalDateTime.now();
        int updated;
        if (version <= 0) {
            try {
                updated = jdbc.sql("""
                        INSERT INTO mail_template(code, subject, html_body, updated_by, updated_by_name, updated_at, version)
                        VALUES (:code, :subject, :html, :userId, :userName, :now, 1)
                        """).param("code", SETTLEMENT).param("subject", cleanSubject).param("html", html)
                        .param("userId", user.id()).param("userName", user.displayName()).param("now", now).update();
            } catch (DuplicateKeyException ex) {
                updated = 0;
            }
        } else {
            updated = jdbc.sql("""
                    UPDATE mail_template SET subject=:subject, html_body=:html, updated_by=:userId,
                        updated_by_name=:userName, updated_at=:now, version=version+1
                    WHERE code=:code AND version=:version
                    """).param("code", SETTLEMENT).param("subject", cleanSubject).param("html", html)
                    .param("userId", user.id()).param("userName", user.displayName()).param("now", now)
                    .param("version", version).update();
        }
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "模板已被其他管理员修改，请刷新后再编辑");
        }
        return view();
    }

    @Transactional
    public TemplateView reset() {
        jdbc.sql("DELETE FROM mail_template WHERE code=:code").param("code", SETTLEMENT).update();
        return view();
    }

    private Optional<Template> stored() {
        return jdbc.sql("""
                SELECT subject, html_body, updated_at, updated_by_name, version
                FROM mail_template WHERE code=:code
                """).param("code", SETTLEMENT)
                .query((rs, n) -> new Template(rs.getString(1), rs.getString(2), true,
                        rs.getObject(3, LocalDateTime.class), rs.getString(4), rs.getInt(5)))
                .optional();
    }

    String defaultHtml() {
        String cached = defaultHtml;
        if (cached != null) return cached;
        try (InputStream in = new ClassPathResource("mail/settlement-default.html").getInputStream()) {
            cached = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("缺少内置邮件模板", ex);
        }
        defaultHtml = cached;
        return cached;
    }

    /** {@code customized=false} 时是内置模板，{@code version} 为 0。 */
    public record Template(String subject, String html, boolean customized, LocalDateTime updatedAt,
                           String updatedByName, int version) {}

    public record TemplateView(Template template, String defaultSubject, String defaultHtml,
                               Map<String, String> variables) {}
}

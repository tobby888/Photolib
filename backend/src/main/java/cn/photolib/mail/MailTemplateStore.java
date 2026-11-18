package cn.photolib.mail;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@RequiredArgsConstructor
public class MailTemplateStore {
    public static final int MAX_SUBJECT = 200;
    public static final int MAX_HTML = 200_000;
    private final JdbcClient jdbc;
    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    public Template current(String code, String subject, String resource) {
        return stored(code).orElseGet(() -> new Template(subject, defaultHtml(resource), false, null, null, 0));
    }

    public Optional<Template> stored(String code) {
        return jdbc.sql("""
                SELECT subject, html_body, updated_at, updated_by_name, version
                FROM mail_template WHERE code=:code
                """).param("code", code)
                .query((rs, row) -> new Template(rs.getString(1), rs.getString(2), true,
                        rs.getObject(3, LocalDateTime.class), rs.getString(4), rs.getInt(5)))
                .optional();
    }

    public void save(String code, String subject, String html, int version, AuthenticatedUser user) {
        String cleanSubject = subject == null ? "" : subject.replaceAll("[\\r\\n]+", " ").trim();
        if (cleanSubject.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写邮件主题");
        if (cleanSubject.length() > MAX_SUBJECT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮件主题最多 " + MAX_SUBJECT + " 个字");
        }
        if (html == null || html.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写邮件正文");
        if (html.length() > MAX_HTML) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮件正文太长（上限 20 万字符）");
        String sql = version <= 0 ? """
                INSERT INTO mail_template(code, subject, html_body, updated_by, updated_by_name, updated_at, version)
                VALUES (:code, :subject, :html, :userId, :userName, :now, 1)
                """ : """
                UPDATE mail_template SET subject=:subject, html_body=:html, updated_by=:userId,
                    updated_by_name=:userName, updated_at=:now, version=version+1
                WHERE code=:code AND version=:version
                """;
        int updated;
        try {
            var statement = jdbc.sql(sql).param("code", code).param("subject", cleanSubject).param("html", html)
                    .param("userId", user.id()).param("userName", user.displayName()).param("now", LocalDateTime.now());
            updated = (version <= 0 ? statement : statement.param("version", version)).update();
        } catch (DuplicateKeyException ex) {
            updated = 0;
        }
        if (updated == 0) throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "模板已被其他管理员修改，请刷新后再编辑");
    }

    public void reset(String code) {
        jdbc.sql("DELETE FROM mail_template WHERE code=:code").param("code", code).update();
    }

    public String defaultHtml(String resource) {
        return defaults.computeIfAbsent(resource, name -> {
            try (var input = new ClassPathResource("mail/" + name).getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("缺少内置邮件模板：" + name, ex);
            }
        });
    }

    public record Template(String subject, String html, boolean customized, LocalDateTime updatedAt,
                           String updatedByName, int version) {}

    public record View(Template template, String defaultSubject, String defaultHtml, Map<String, String> variables) {}
}

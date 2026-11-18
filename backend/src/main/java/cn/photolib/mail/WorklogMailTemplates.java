package cn.photolib.mail;

import cn.photolib.admin.BrandSnapshotService.BrandSnapshot;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.mail.MailTemplateStore.View;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorklogMailTemplates {
    private static final Set<String> HTML_VARIABLES = Set.of("logo");
    private final MailTemplateStore store;
    private final MailLayoutService layouts;
    private final SmtpProperties smtp;

    public View view(Kind kind) {
        return new View(store.current(kind.name(), kind.subject, kind.resource), kind.subject,
                store.defaultHtml(kind.resource), variables(kind));
    }

    @Transactional
    public View save(Kind kind, String subject, String html, int version, AuthenticatedUser user) {
        store.save(kind.name(), subject, html, version, user);
        return view(kind);
    }

    @Transactional
    public View reset(Kind kind) {
        store.reset(kind.name());
        return view(kind);
    }

    public Rendered render(Kind kind, Details details, BrandSnapshot brand, boolean preview,
                           String subject, String html, String layout) {
        if (subject == null || html == null) {
            var template = store.current(kind.name(), kind.subject, kind.resource);
            if (subject == null) subject = template.subject();
            if (html == null) html = template.html();
        }
        Map<String, String> values = MailRendering.branding(brand, preview);
        values.put("recipientName", details.recipientName());
        values.put("projectTitle", fallback(details.projectTitle()));
        values.put("workDate", details.workDate().toString());
        values.put("shootingTime", duration(details.shootingMinutes()));
        values.put("retouchingTime", duration(details.retouchingMinutes()));
        values.put("totalTime", duration(details.shootingMinutes() + details.retouchingMinutes()));
        values.put("reason", fallback(details.reason()));
        if (kind == Kind.WORKLOG_SUBMITTED) {
            values.put("submitIp", fallback(details.submitIp()));
            values.put("userAgent", fallback(details.userAgent()));
        } else {
            values.put("reviewerName", fallback(details.reviewerName()));
        }
        values.put("siteUrl", smtp.siteUrl() == null ? "" : smtp.siteUrl());
        String fullHtml = MailLayoutService.wrap(layout == null ? layouts.current().html() : layout, html);
        String rendered = MailRendering.fill(fullHtml, values, true, HTML_VARIABLES);
        return new Rendered(MailRendering.subject(subject, values, HTML_VARIABLES), rendered, Jsoup.parse(rendered).wholeText().strip());
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private static String duration(int minutes) {
        return minutes < 60 ? minutes + " 分钟" : minutes / 60 + " 小时"
                + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分钟");
    }

    private static Map<String, String> variables(Kind kind) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("recipientName", "收件人姓名");
        variables.put("projectTitle", "选题项目名称");
        variables.put("workDate", "工作日期");
        variables.put("shootingTime", "拍摄时长");
        variables.put("retouchingTime", "修图时长");
        variables.put("totalTime", "合计时长");
        variables.put("reason", "申报原由（工时备注）");
        if (kind == Kind.WORKLOG_SUBMITTED) {
            variables.put("submitIp", "申报 IP");
            variables.put("userAgent", "申报浏览器 User-Agent");
        } else {
            variables.put("reviewerName", "审核人姓名");
        }
        variables.put("siteName", "品牌名称");
        variables.put("siteUrl", "站点地址（未配置时为空）");
        return Collections.unmodifiableMap(variables);
    }

    public enum Kind {
        WORKLOG_SUBMITTED("【{{siteName}}】工时申报成功：{{projectTitle}}", "worklog-submitted.html"),
        WORKLOG_APPROVED("【{{siteName}}】工时审核通过：{{projectTitle}}", "worklog-approved.html");

        private final String subject;
        private final String resource;

        Kind(String subject, String resource) {
            this.subject = subject;
            this.resource = resource;
        }
    }

    public record Details(String recipientName, String projectTitle, LocalDate workDate, int shootingMinutes,
                          int retouchingMinutes, String reason, String submitIp, String userAgent, String reviewerName) {}

    public record Rendered(String subject, String html, String plainText) {}
}

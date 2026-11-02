package cn.photolib.settlement;

import cn.photolib.admin.BrandSnapshotService.BrandSnapshot;
import cn.photolib.mail.OutgoingMail;
import cn.photolib.settlement.SettlementStatementService.AdoptionLine;
import cn.photolib.settlement.SettlementStatementService.ProjectLine;
import cn.photolib.settlement.SettlementStatementService.ReviewEvent;
import cn.photolib.settlement.SettlementStatementService.Statement;
import cn.photolib.settlement.SettlementStatementService.Totals;
import cn.photolib.settlement.SettlementStatementService.WorklogLine;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一份清算单套进邮件模板。
 *
 * <p>模板里用 {@code {{name}}} 引用变量，{@code {{#name}}…{{/name}}} 包住的部分只在变量非空时保留。
 * 普通变量一律 HTML 转义后再填（姓名、选题标题都是用户输入）；只有 {@link #HTML_VARIABLES} 这几个
 * 由本类生成的 HTML 片段原样填入。认不出的 {@code {{…}}} 原样留着，管理员在预览里一眼就能看到拼错了。</p>
 *
 * <p>表格全部用行内样式和 {@code <table>} 排版：邮件客户端（尤其 Outlook、网页版 QQ 邮箱）
 * 会丢掉 {@code <style>}、不支持 flex / grid。</p>
 *
 * <p>同一份模板套出三种成品，见 {@link Target}。</p>
 */
public final class SettlementMailRenderer {
    public static final String LOGO_CONTENT_ID = "brand-logo";
    static final Set<String> HTML_VARIABLES = Set.of("logo", "summaryCards", "projectTable", "worklogTable",
            "adoptionTable");
    /** 站内信和预览里的链接用站内路径：放在 srcdoc iframe 里时按外层页面的地址解析，换个域名访问也不会断。 */
    static final String IN_APP_LINK_BASE = "/#/";
    /** 模板编辑器里列出来的变量说明，顺序即展示顺序。 */
    public static final Map<String, String> VARIABLES = variables();

    private static final Pattern SECTION = Pattern.compile("\\{\\{#\\s*([A-Za-z]+)\\s*}}(.*?)\\{\\{/\\s*\\1\\s*}}", Pattern.DOTALL);
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*}}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final String GREEN = "#2f8f5b";
    private static final String AMBER = "#b26a00";
    private static final String RED = "#c0392b";
    private static final String MUTED = "#7a8a9c";

    private SettlementMailRenderer() {
    }

    /**
     * 套出来给谁看。
     * <ul>
     *   <li>{@code EMAIL}：真正发出去的邮件。图标用 {@code cid:brand-logo} 引用内嵌附件；图片、选题链接
     *       只有配置了站点地址（{@code SMTP_SITE_BASE_URL}）才有，邮箱里没法用站内路径。</li>
     *   <li>{@code PREVIEW}：发送前预览和导出 PDF。图标用 data: URI 内联（浏览器里没有 cid:），链接用站内路径。</li>
     *   <li>{@code INBOX}：存进站内信的快照。图标同样存成 {@code cid:brand-logo}，读取时再换成当前图标
     *       （{@link #inlineLogo}），免得每份快照各存一遍图片；链接用站内路径；「登录查看」按钮不要，人已经在站内。</li>
     * </ul>
     */
    public enum Target { EMAIL, PREVIEW, INBOX }

    public static Rendered render(String subjectTemplate, String htmlTemplate, Statement statement,
                                  BrandSnapshot brand, String senderName, String siteUrl,
                                  LocalDateTime generatedAt, Target target) {
        String logoSrc = target == Target.PREVIEW ? dataUri(brand) : "cid:" + LOGO_CONTENT_ID;
        String linkBase = target == Target.EMAIL ? blankToNull(siteUrl) : IN_APP_LINK_BASE;
        if (target == Target.INBOX) siteUrl = null;
        Totals totals = statement.totals();
        Map<String, String> values = new HashMap<>();
        values.put("siteName", brand.title());
        values.put("siteSlogan", brand.slogan());
        values.put("logoSrc", logoSrc);
        values.put("logo", "<img src=\"" + escape(logoSrc) + "\" width=\"44\" height=\"44\" alt=\"" + escape(brand.title())
                + "\" style=\"display:block;border:0;outline:none;border-radius:10px;width:44px;height:44px;\">");
        values.put("recipientName", statement.recipient().displayName());
        values.put("recipientEmail", nullToEmpty(statement.recipient().email()));
        values.put("periodFrom", DATE.format(statement.from()));
        values.put("periodTo", DATE.format(statement.to()));
        values.put("generatedAt", DATE_TIME.format(generatedAt));
        values.put("senderName", nullToEmpty(senderName));
        values.put("siteUrl", nullToEmpty(siteUrl));
        values.put("confirmedHours", duration(totals.confirmedMinutes()));
        values.put("shootingHours", duration(totals.confirmedShootingMinutes()));
        values.put("retouchingHours", duration(totals.confirmedRetouchingMinutes()));
        values.put("pendingHours", duration(totals.pendingMinutes()));
        values.put("worklogCount", String.valueOf(totals.worklogCount()));
        values.put("confirmedCount", String.valueOf(totals.confirmedCount()));
        values.put("pendingCount", String.valueOf(totals.pendingCount()));
        values.put("rejectedCount", String.valueOf(totals.rejectedCount()));
        values.put("adoptedPhotos", String.valueOf(totals.adoptedPhotos()));
        values.put("adoptionCount", String.valueOf(totals.adoptionCount()));
        values.put("projectCount", String.valueOf(totals.projectCount()));
        values.put("uploadedPhotos", String.valueOf(totals.uploadedPhotos()));
        values.put("summaryCards", summaryCards(totals));
        values.put("projectTable", projectTable(statement, linkBase));
        values.put("worklogTable", worklogTable(statement));
        values.put("adoptionTable", adoptionTable(statement, linkBase));

        String subject = fill(subjectTemplate, values, false).replaceAll("[\\r\\n]+", " ").trim();
        if (subject.length() > 200) subject = subject.substring(0, 200);
        String html = fill(htmlTemplate, values, true);
        List<OutgoingMail.InlineImage> images = target != Target.EMAIL ? List.of()
                : List.of(new OutgoingMail.InlineImage(LOGO_CONTENT_ID, brand.icon(), brand.iconContentType()));
        return new Rendered(subject, html, plainText(statement, brand, siteUrl), images);
    }

    /** 站内信快照里的 {@code cid:brand-logo} 换成当前品牌图标，iframe 里才显示得出来。 */
    public static String inlineLogo(String html, BrandSnapshot brand) {
        return html == null ? "" : html.replace("cid:" + LOGO_CONTENT_ID, dataUri(brand));
    }

    private static String dataUri(BrandSnapshot brand) {
        return "data:" + brand.iconContentType() + ";base64," + Base64.getEncoder().encodeToString(brand.icon());
    }

    static String fill(String template, Map<String, String> values, boolean html) {
        if (template == null) return "";
        Matcher sections = SECTION.matcher(template);
        StringBuilder withSections = new StringBuilder();
        while (sections.find()) {
            String value = values.get(sections.group(1));
            sections.appendReplacement(withSections,
                    Matcher.quoteReplacement(value == null || value.isBlank() ? "" : sections.group(2)));
        }
        sections.appendTail(withSections);
        Matcher variables = VARIABLE.matcher(withSections);
        StringBuilder out = new StringBuilder();
        while (variables.find()) {
            String name = variables.group(1);
            String value = values.get(name);
            String replacement;
            if (value == null) replacement = variables.group(0);
            else if (!html) replacement = HTML_VARIABLES.contains(name) ? "" : value;
            else replacement = HTML_VARIABLES.contains(name) ? value : escape(value);
            variables.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        variables.appendTail(out);
        return out.toString();
    }

    // ---- 汇总卡片 ----

    private static String summaryCards(Totals totals) {
        StringBuilder html = new StringBuilder();
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        card(html, "已确认工时", duration(totals.confirmedMinutes()),
                "拍摄 " + duration(totals.confirmedShootingMinutes()) + " · 修图 " + duration(totals.confirmedRetouchingMinutes()),
                "#4682B4", true);
        card(html, "待审核", totals.pendingCount() + " 条",
                totals.pendingCount() == 0 ? "没有待审核的记录" : "共 " + duration(totals.pendingMinutes()), AMBER, true);
        card(html, "被退回", totals.rejectedCount() + " 条",
                totals.rejectedCount() == 0 ? "没有被退回的记录" : "原因见下方明细", RED, true);
        card(html, "被引图片", totals.adoptedPhotos() + " 张", "共被引用 " + totals.adoptionCount() + " 次", GREEN, false);
        html.append("</tr></table>");
        return html.toString();
    }

    private static void card(StringBuilder html, String label, String value, String note, String color, boolean gap) {
        html.append("<td width=\"25%\" valign=\"top\" style=\"padding:0 ").append(gap ? "8px" : "0").append(" 0 0;\">")
                .append("<div style=\"background:#f6f9fc;border:1px solid #e3e9f0;border-top:3px solid ").append(color)
                .append(";border-radius:10px;padding:12px 12px 10px;\">")
                .append("<div style=\"font-size:12px;color:").append(MUTED).append(";\">").append(escape(label)).append("</div>")
                .append("<div style=\"font-size:18px;font-weight:700;color:").append(color)
                .append(";margin-top:4px;line-height:1.3;\">").append(escape(value)).append("</div>")
                .append("<div style=\"font-size:11px;color:").append(MUTED).append(";margin-top:4px;line-height:1.5;\">")
                .append(escape(note)).append("</div></div></td>");
    }

    // ---- 参与的选题 ----

    private static String projectTable(Statement statement, String linkBase) {
        if (statement.projects().isEmpty()) return empty("清算周期内没有上传过图片。");
        Totals totals = statement.totals();
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-size:13px;color:#4a5a6a;margin:0 0 10px;line-height:1.7;\">共参与 <b style=\"color:#1f3b57;\">")
                .append(totals.projectCount()).append("</b> 个选题，上传 <b style=\"color:#1f3b57;\">")
                .append(totals.uploadedPhotos()).append("</b> 张图片。</div>");
        tableHead(html, "选题", "上传图片", "上传时间");
        int index = 0;
        for (ProjectLine line : statement.projects()) {
            String first = DATE.format(line.firstUploadAt());
            String last = DATE.format(line.lastUploadAt());
            html.append("<tr style=\"background:").append(index++ % 2 == 0 ? "#ffffff" : "#fafcfe").append(";\">")
                    .append("<td style=\"padding:8px 10px;border-bottom:1px solid #eef2f6;color:#1f2d3d;\">")
                    .append(link(linkBase, "projects/" + line.projectId(), escape(orDash(line.projectTitle()))))
                    .append("</td>")
                    .append("<td style=\"padding:8px 10px;border-bottom:1px solid #eef2f6;color:#1f2d3d;white-space:nowrap;\"><b>")
                    .append(line.photoCount()).append("</b> 张</td>")
                    .append(cell(first.equals(last) ? first : first + " 至 " + last, true))
                    .append("</tr>");
        }
        html.append("</table>");
        return html.toString();
    }

    private static void tableHead(StringBuilder html, String... heads) {
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"border:1px solid #e3e9f0;border-radius:8px;border-collapse:separate;font-size:13px;\">")
                .append("<tr style=\"background:#f2f6fa;\">");
        for (String head : heads) {
            html.append("<th align=\"left\" style=\"padding:9px 10px;font-size:12px;font-weight:600;color:")
                    .append(MUTED).append(";border-bottom:1px solid #e3e9f0;white-space:nowrap;\">").append(head).append("</th>");
        }
        html.append("</tr>");
    }

    /**
     * 站内页面的链接；{@code linkBase} 为空（邮件但没配站点地址）时只输出文字。
     * {@code innerHtml} 必须已经转义过。
     */
    private static String link(String linkBase, String path, String innerHtml) {
        if (linkBase == null) return innerHtml;
        return "<a href=\"" + escape(linkBase + path) + "\" target=\"_blank\" rel=\"noopener noreferrer\" "
                + "style=\"color:#356b96;text-decoration:underline;\">" + innerHtml + "</a>";
    }

    // ---- 工时明细 ----

    private static String worklogTable(Statement statement) {
        if (statement.worklogs().isEmpty()) return empty("清算周期内没有工时记录。");
        StringBuilder html = new StringBuilder();
        for (WorklogLine line : statement.worklogs()) worklogCard(html, line);
        if (statement.hiddenWorklogs() > 0) {
            html.append(note("另有 " + statement.hiddenWorklogs() + " 条工时记录未在清算单中列出（合计已包含），请登录系统查看。"));
        }
        return html.toString();
    }

    private static void worklogCard(StringBuilder html, WorklogLine line) {
        String color = statusColor(line.status());
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"margin:0 0 12px;border:1px solid #e3e9f0;border-left:4px solid ").append(color)
                .append(";border-radius:8px;background:#ffffff;\"><tr><td style=\"padding:12px 14px;\">");
        // 第一行：日期 + 状态
        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
                .append("<td style=\"font-size:15px;font-weight:700;color:#1f3b57;\">")
                .append(escape(DATE.format(line.workDate()))).append(" <span style=\"font-weight:400;color:").append(MUTED)
                .append(";font-size:12px;\">").append(weekday(line.workDate())).append(" · 工时 #").append(line.id())
                .append("</span></td><td align=\"right\">").append(statusChip(line.status())).append("</td></tr></table>");
        // 第二行：来源
        StringBuilder source = new StringBuilder();
        source.append("选题：").append(escape(orDash(line.projectTitle())));
        if (line.viaShareLink()) {
            source.append(" · 来源：上传链接申报");
            if (line.shareLinkName() != null && !line.shareLinkName().isBlank()) {
                source.append("（").append(escape(line.shareLinkName())).append("）");
            }
        } else {
            source.append(" · 需求：").append(escape(orDash(line.requestTitle())));
            if (line.campus() != null) source.append(" · 校区：").append(escape(line.campus()));
        }
        html.append("<div style=\"font-size:13px;color:#4a5a6a;margin-top:6px;line-height:1.7;\">").append(source).append("</div>");
        // 第三行：时长
        html.append("<div style=\"font-size:13px;color:#1f2d3d;margin-top:4px;line-height:1.7;\">")
                .append("拍摄 <b>").append(duration(line.shootingMinutes())).append("</b>　修图 <b>")
                .append(duration(line.retouchingMinutes())).append("</b>　合计 <b style=\"color:").append(color).append(";\">")
                .append(duration(line.totalMinutes())).append("</b></div>");
        if (line.remark() != null && !line.remark().isBlank()) {
            html.append("<div style=\"font-size:12px;color:").append(MUTED).append(";margin-top:4px;line-height:1.7;\">说明：")
                    .append(multiline(line.remark())).append("</div>");
        }
        if ("REJECTED".equals(line.status())) {
            html.append("<div style=\"margin-top:8px;background:#fdf0ee;border:1px solid #f3c7c0;border-radius:6px;")
                    .append("padding:8px 10px;font-size:13px;color:").append(RED).append(";line-height:1.7;\"><b>退回原因：</b>")
                    .append(multiline(orDash(line.rejectReason()))).append("</div>");
        }
        timeline(html, line);
        html.append("</td></tr></table>");
    }

    /** 每一次提交 / 退回 / 确认。没有记录（V71 之前就删掉了审核痕迹）时退回到工时本身的时间。 */
    private static void timeline(StringBuilder html, WorklogLine line) {
        html.append("<div style=\"margin-top:10px;padding-top:8px;border-top:1px dashed #e3e9f0;\">")
                .append("<div style=\"font-size:12px;color:").append(MUTED).append(";margin-bottom:4px;\">申报记录</div>");
        List<ReviewEvent> events = line.events();
        if (events.isEmpty()) {
            timelineRow(html, line.createdAt(), "填报", "#4682B4", null, false);
            if (line.reviewedAt() != null && !"SUBMITTED".equals(line.status())) {
                timelineRow(html, line.reviewedAt(), statusLabel(line.status()), statusColor(line.status()),
                        reviewerSuffix(line.reviewerName()), false);
            }
        } else {
            int submissions = 0;
            for (ReviewEvent event : events) {
                switch (event.action()) {
                    case "SUBMITTED" -> {
                        submissions++;
                        String detail = event.shootingMinutes() == null ? null
                                : "拍摄 " + duration(event.shootingMinutes()) + "，修图 " + duration(nonNull(event.retouchingMinutes()));
                        timelineRow(html, event.at(), submissions == 1 ? "提交申报" : "第 " + submissions + " 次提交",
                                "#4682B4", detail, event.backfilled());
                    }
                    case "REJECTED" -> timelineRow(html, event.at(), "退回", RED,
                            joinNonBlank(reviewerSuffix(event.actorName()),
                                    event.reason() == null || event.reason().isBlank() ? null : "原因：" + event.reason()),
                            event.backfilled());
                    case "CONFIRMED" -> timelineRow(html, event.at(), "确认", GREEN,
                            reviewerSuffix(event.actorName()), event.backfilled());
                    default -> { }
                }
            }
        }
        html.append("</div>");
    }

    private static void timelineRow(StringBuilder html, LocalDateTime at, String action, String color, String detail,
                                    boolean approximate) {
        html.append("<div style=\"font-size:12px;line-height:1.8;color:#4a5a6a;\">")
                .append("<span style=\"display:inline-block;width:7px;height:7px;border-radius:50%;background:")
                .append(color).append(";margin-right:6px;vertical-align:middle;\"></span>")
                .append("<span style=\"color:#1f2d3d;\">").append(at == null ? "时间未知" : escape(DATE_TIME.format(at)))
                .append(approximate ? "<span style=\"color:" + MUTED + ";\">（约）</span>" : "")
                .append("</span>　<b style=\"color:").append(color).append(";\">").append(escape(action)).append("</b>");
        if (detail != null && !detail.isBlank()) html.append("　").append(multiline(detail));
        html.append("</div>");
    }

    // ---- 被引明细 ----

    private static String adoptionTable(Statement statement, String linkBase) {
        if (statement.adoptions().isEmpty()) return empty("清算周期内没有图片被引用。");
        StringBuilder html = new StringBuilder();
        tableHead(html, "被引时间", "选题", "图片", "标记人");
        int index = 0;
        for (AdoptionLine line : statement.adoptions()) {
            String background = index++ % 2 == 0 ? "#ffffff" : "#fafcfe";
            html.append("<tr style=\"background:").append(background).append(";\">")
                    .append(cell(DATE_TIME.format(line.adoptedAt()), true))
                    .append(cell(orDash(line.projectTitle()), false))
                    .append("<td style=\"padding:8px 10px;border-bottom:1px solid #eef2f6;color:#1f2d3d;\">")
                    .append(link(linkBase, "photos/" + line.photoId(), "#" + line.photoId()
                            + (line.photoTitle() == null || line.photoTitle().isBlank() ? "" : " " + escape(line.photoTitle()))))
                    .append(line.remark() == null || line.remark().isBlank() ? ""
                            : "<div style=\"font-size:11px;color:" + MUTED + ";\">备注：" + escape(line.remark()) + "</div>")
                    .append("</td>")
                    .append(cell(orDash(line.adoptedBy()), true))
                    .append("</tr>");
        }
        html.append("</table>");
        if (statement.hiddenAdoptions() > 0) {
            html.append(note("另有 " + statement.hiddenAdoptions() + " 条被引记录未在清算单中列出（合计已包含），请登录系统查看。"));
        }
        return html.toString();
    }

    private static String cell(String value, boolean nowrap) {
        return "<td style=\"padding:8px 10px;border-bottom:1px solid #eef2f6;color:#1f2d3d;"
                + (nowrap ? "white-space:nowrap;" : "") + "\">" + escape(value) + "</td>";
    }

    // ---- 纯文本版本（给不显示 HTML 的客户端） ----

    private static String plainText(Statement statement, BrandSnapshot brand, String siteUrl) {
        Totals totals = statement.totals();
        StringBuilder text = new StringBuilder();
        text.append(brand.title()).append(" · 工作清算单\n")
                .append("清算周期：").append(DATE.format(statement.from())).append(" 至 ").append(DATE.format(statement.to())).append("\n\n")
                .append(statement.recipient().displayName()).append("，你好：\n\n")
                .append("已确认工时：").append(duration(totals.confirmedMinutes()))
                .append("（拍摄 ").append(duration(totals.confirmedShootingMinutes()))
                .append("，修图 ").append(duration(totals.confirmedRetouchingMinutes())).append("）\n")
                .append("待审核：").append(totals.pendingCount()).append(" 条；被退回：").append(totals.rejectedCount()).append(" 条\n")
                .append("被引图片：").append(totals.adoptedPhotos()).append(" 张（共 ").append(totals.adoptionCount()).append(" 次）\n")
                .append("参与选题：").append(totals.projectCount()).append(" 个，上传图片 ").append(totals.uploadedPhotos()).append(" 张\n\n")
                .append("【参与的选题】\n");
        if (statement.projects().isEmpty()) text.append("无\n");
        for (ProjectLine line : statement.projects()) {
            text.append("- ").append(orDash(line.projectTitle())).append("：上传 ").append(line.photoCount()).append(" 张\n");
        }
        text.append("\n【工时详情】\n");
        if (statement.worklogs().isEmpty()) text.append("无\n");
        for (WorklogLine line : statement.worklogs()) {
            text.append("- ").append(DATE.format(line.workDate())).append(" ").append(statusLabel(line.status()))
                    .append(" ").append(orDash(line.projectTitle()))
                    .append(line.viaShareLink() ? "（上传链接申报）" : " / " + orDash(line.requestTitle()))
                    .append("：拍摄 ").append(duration(line.shootingMinutes()))
                    .append("，修图 ").append(duration(line.retouchingMinutes())).append("\n");
            if ("REJECTED".equals(line.status())) text.append("  退回原因：").append(orDash(line.rejectReason())).append("\n");
        }
        text.append("\n【被引详情】\n");
        if (statement.adoptions().isEmpty()) text.append("无\n");
        for (AdoptionLine line : statement.adoptions()) {
            text.append("- ").append(DATE_TIME.format(line.adoptedAt())).append(" ").append(orDash(line.projectTitle()))
                    .append(" 图片 #").append(line.photoId()).append("\n");
        }
        if (siteUrl != null && !siteUrl.isBlank()) text.append("\n登录查看：").append(siteUrl).append("\n");
        return text.toString();
    }

    // ---- 小工具 ----

    static String duration(int minutes) {
        if (minutes <= 0) return "0 分钟";
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) return rest + " 分钟";
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分";
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "CONFIRMED" -> "已确认";
            case "SUBMITTED" -> "待审核";
            case "REJECTED" -> "已退回";
            default -> status;
        };
    }

    private static String statusColor(String status) {
        return switch (status) {
            case "CONFIRMED" -> GREEN;
            case "REJECTED" -> RED;
            default -> AMBER;
        };
    }

    private static String statusChip(String status) {
        String color = statusColor(status);
        String background = switch (status) {
            case "CONFIRMED" -> "#e9f6ef";
            case "REJECTED" -> "#fdf0ee";
            default -> "#fff5e5";
        };
        return "<span style=\"display:inline-block;font-size:12px;font-weight:600;color:" + color + ";background:"
                + background + ";border-radius:999px;padding:2px 10px;white-space:nowrap;\">" + statusLabel(status) + "</span>";
    }

    private static String empty(String text) {
        return "<div style=\"background:#f6f9fc;border:1px dashed #d5dee8;border-radius:8px;padding:18px;text-align:center;"
                + "font-size:13px;color:" + MUTED + ";\">" + escape(text) + "</div>";
    }

    private static String note(String text) {
        return "<div style=\"font-size:12px;color:" + AMBER + ";margin-top:8px;\">" + escape(text) + "</div>";
    }

    private static String weekday(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() - 1];
    }

    private static String reviewerSuffix(String name) {
        return name == null || name.isBlank() ? null : "审核人：" + name;
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + "；" + b;
    }

    private static int nonNull(Integer value) {
        return value == null ? 0 : value;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String multiline(String value) {
        return escape(value).replace("\r\n", "\n").replace("\n", "<br>");
    }

    static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }

    private static Map<String, String> variables() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("siteName", "面板品牌名称");
        map.put("siteSlogan", "品牌 Slogan");
        map.put("logo", "品牌图标（完整的 <img> 标签，44×44）");
        map.put("logoSrc", "品牌图标地址，自己写 <img src=\"{{logoSrc}}\"> 时用");
        map.put("recipientName", "收件人姓名");
        map.put("recipientEmail", "收件人邮箱");
        map.put("periodFrom", "清算开始日期");
        map.put("periodTo", "清算结束日期");
        map.put("summaryCards", "汇总卡片（已确认工时、待审核、被退回、被引图片）");
        map.put("projectTable", "参与的选题（期内上传过图片的选题及每个选题上传的张数，选题可点开）");
        map.put("worklogTable", "工时详情（每条的申报时间、时长、状态、退回原因和审核记录）");
        map.put("adoptionTable", "被引详情表（图片可点开详情页）");
        map.put("confirmedHours", "已确认工时合计");
        map.put("shootingHours", "已确认拍摄时长");
        map.put("retouchingHours", "已确认修图时长");
        map.put("pendingHours", "待审核时长");
        map.put("worklogCount", "工时记录条数");
        map.put("confirmedCount", "已确认条数");
        map.put("pendingCount", "待审核条数");
        map.put("rejectedCount", "被退回条数");
        map.put("adoptedPhotos", "被引图片张数（去重）");
        map.put("adoptionCount", "被引次数");
        map.put("projectCount", "参与的选题个数");
        map.put("uploadedPhotos", "期内上传的图片张数");
        map.put("senderName", "发送人");
        map.put("generatedAt", "生成时间");
        map.put("siteUrl", "站点地址（未配置时为空；可用 {{#siteUrl}}…{{/siteUrl}} 包住只在有地址时显示的内容）");
        return java.util.Collections.unmodifiableMap(map);
    }

    public record Rendered(String subject, String html, String plainText, List<OutgoingMail.InlineImage> inlineImages) {}
}

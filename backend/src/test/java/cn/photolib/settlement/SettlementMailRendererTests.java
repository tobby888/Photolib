package cn.photolib.settlement;

import cn.photolib.admin.BrandSnapshotService.BrandSnapshot;
import cn.photolib.settlement.SettlementStatementService.AdoptionLine;
import cn.photolib.settlement.SettlementStatementService.Recipient;
import cn.photolib.settlement.SettlementStatementService.ReviewEvent;
import cn.photolib.settlement.SettlementStatementService.Statement;
import cn.photolib.settlement.SettlementStatementService.Totals;
import cn.photolib.settlement.SettlementStatementService.WorklogLine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 模板变量替换、转义和工时明细里的申报记录 / 退回原因。 */
class SettlementMailRendererTests {
    private static final BrandSnapshot BRAND = new BrandSnapshot("光影<工作站>", "记录校园", new byte[]{1, 2, 3}, "image/png");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 9, 30);

    @Test
    void escapesUserTextButKeepsGeneratedFragments() {
        Statement statement = statement(List.of(), List.of());
        String html = SettlementMailRenderer.render("{{recipientName}}", "<p>{{recipientName}}</p>{{worklogTable}}",
                statement, BRAND, "部长", null, NOW, false).html();

        assertThat(html).contains("<p>&lt;script&gt;张三</p>");
        assertThat(html).doesNotContain("<script>");
        // 生成的片段原样进来（是一段 HTML，而不是被转义成文字）。
        assertThat(html).contains("<div style=").contains("清算周期内没有工时记录。");
    }

    @Test
    void sectionsAreDroppedWhenTheVariableIsEmptyAndUnknownPlaceholdersStay() {
        Statement statement = statement(List.of(), List.of());
        String template = "A{{#siteUrl}}<a href=\"{{siteUrl}}\">去看看</a>{{/siteUrl}}B {{notAVariable}}";

        String withoutUrl = SettlementMailRenderer.render("s", template, statement, BRAND, "部长", null, NOW, false).html();
        String withUrl = SettlementMailRenderer.render("s", template, statement, BRAND, "部长",
                "https://photo.example.cn/#/", NOW, false).html();

        assertThat(withoutUrl).isEqualTo("AB {{notAVariable}}");
        assertThat(withUrl).isEqualTo("A<a href=\"https://photo.example.cn/#/\">去看看</a>B {{notAVariable}}");
    }

    @Test
    void subjectIsPlainTextOnOneLine() {
        Statement statement = statement(List.of(), List.of());
        String subject = SettlementMailRenderer.render("【{{siteName}}】\r\n{{recipientName}}{{worklogTable}}", "x",
                statement, BRAND, "部长", null, NOW, false).subject();

        // 主题不转义（不是 HTML）、不能带换行（会变成邮件头注入），HTML 片段在主题里为空。
        assertThat(subject).isEqualTo("【光影<工作站>】 <script>张三");
    }

    @Test
    void logoIsAnInlineAttachmentWhenSendingAndADataUriWhenPreviewing() {
        Statement statement = statement(List.of(), List.of());
        SettlementMailRenderer.Rendered sent = SettlementMailRenderer.render("s", "{{logo}}", statement, BRAND,
                "部长", null, NOW, false);
        SettlementMailRenderer.Rendered preview = SettlementMailRenderer.render("s", "{{logoSrc}}", statement, BRAND,
                "部长", null, NOW, true);

        assertThat(sent.html()).contains("src=\"cid:brand-logo\"");
        assertThat(sent.inlineImages()).singleElement().satisfies(image -> {
            assertThat(image.contentId()).isEqualTo("brand-logo");
            assertThat(image.contentType()).isEqualTo("image/png");
        });
        assertThat(preview.html()).isEqualTo("data:image/png;base64,AQID");
        assertThat(preview.inlineImages()).isEmpty();
    }

    @Test
    void worklogDetailsListEverySubmissionAndEveryRejectionReason() {
        List<ReviewEvent> events = new ArrayList<>(List.of(
                new ReviewEvent("SUBMITTED", LocalDateTime.of(2026, 10, 3, 14, 20), "李四", null, 120, 60, false),
                new ReviewEvent("REJECTED", LocalDateTime.of(2026, 10, 4, 9, 5), "王部长", "时长<填多了>", null, null, false),
                new ReviewEvent("SUBMITTED", LocalDateTime.of(2026, 10, 5, 10, 0), "李四", null, 90, 30, false),
                new ReviewEvent("CONFIRMED", LocalDateTime.of(2026, 10, 6, 18, 0), "王部长", null, null, null, false)));
        WorklogLine confirmed = new WorklogLine(11, LocalDate.of(2026, 10, 2), "运动会", "开幕式", "北校区", false, null,
                90, 30, "CONFIRMED", "补拍", null, LocalDateTime.of(2026, 10, 3, 14, 0), null, "王部长", "S1", events);
        WorklogLine rejected = new WorklogLine(12, LocalDate.of(2026, 10, 8), "运动会", null, null, true, "现场上传",
                0, 45, "REJECTED", null, "照片不在相册里", LocalDateTime.of(2026, 10, 8, 20, 0),
                LocalDateTime.of(2026, 10, 9, 8, 0), "王部长", "S1", new ArrayList<>());

        String html = SettlementMailRenderer.render("s", "{{worklogTable}}", statement(List.of(confirmed, rejected),
                List.of()), BRAND, "部长", null, NOW, false).html();

        assertThat(html).contains("2026-10-03 14:20").contains("提交申报").contains("拍摄 2 小时，修图 1 小时");
        assertThat(html).contains("2026-10-04 09:05").contains("退回").contains("原因：时长&lt;填多了&gt;");
        assertThat(html).contains("2026-10-05 10:00").contains("第 2 次提交").contains("拍摄 1 小时 30 分，修图 30 分钟");
        assertThat(html).contains("2026-10-06 18:00").contains("确认").contains("审核人：王部长");
        // 没有审核记录的旧工时：至少有填报时间和当前状态，退回原因单独醒目列出。
        assertThat(html).contains("2026-10-08 20:00").contains("退回原因：</b>照片不在相册里");
        assertThat(html).contains("上传链接申报（现场上传）");
        assertThat(html).contains("工时 #11").contains("周五");
    }

    @Test
    void adoptionTableAndSummary() {
        AdoptionLine adoption = new AdoptionLine(LocalDateTime.of(2026, 10, 10, 11, 0), "校报 10 月刊", 501,
                "IMG_0001.jpg", "北校区", "编辑甲", "封面");
        WorklogLine pending = new WorklogLine(13, LocalDate.of(2026, 10, 9), "运动会", "闭幕式", "北校区", false, null,
                30, 0, "SUBMITTED", null, null, LocalDateTime.of(2026, 10, 9, 21, 0), null, null, "S1", new ArrayList<>());
        SettlementMailRenderer.Rendered rendered = SettlementMailRenderer.render("s",
                "{{summaryCards}}|{{adoptionTable}}|{{adoptedPhotos}}|{{pendingHours}}",
                statement(List.of(pending), List.of(adoption, adoption)), BRAND, "部长", null, NOW, false);

        assertThat(rendered.html()).contains("#501 IMG_0001.jpg").contains("校报 10 月刊").contains("备注：封面");
        // 同一张图被引两次：张数 1、次数 2。
        assertThat(rendered.html()).contains("|1|30 分钟").contains("共被引用 2 次");
        assertThat(rendered.plainText()).contains("待审核：1 条").contains("图片 #501");
    }

    @Test
    void defaultTemplateHasNoLeftoverPlaceholders() {
        String template = new MailTemplateService(null).defaultHtml();
        String html = SettlementMailRenderer.render(MailTemplateService.DEFAULT_SUBJECT, template,
                statement(List.of(), List.of()), BRAND, "部长", "https://photo.example.cn/#/", NOW, false).html();

        assertThat(html).doesNotContain("{{").contains("光影&lt;工作站&gt;").contains("cid:brand-logo")
                .contains("https://photo.example.cn/#/");
        assertThat(SettlementMailRenderer.VARIABLES).containsKeys("worklogTable", "adoptionTable", "logo");
        assertThat(Map.copyOf(SettlementMailRenderer.VARIABLES)).hasSize(SettlementMailRenderer.VARIABLES.size());
    }

    private static Statement statement(List<WorklogLine> worklogs, List<AdoptionLine> adoptions) {
        Recipient recipient = new Recipient(1, "zhangsan", "<script>张三", "z@example.cn", "北校区", "成员",
                worklogs.size(), 0, 0, 0, 0, adoptions.size(), 0);
        return new Statement(recipient, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), worklogs, adoptions,
                Totals.of(worklogs, adoptions));
    }
}

package cn.photolib.settlement;

import cn.photolib.admin.BrandSnapshotService.BrandSnapshot;
import cn.photolib.settlement.SettlementMailRenderer.Target;
import cn.photolib.settlement.SettlementStatementService.AdoptionLine;
import cn.photolib.settlement.SettlementStatementService.ProjectLine;
import cn.photolib.settlement.SettlementStatementService.Recipient;
import cn.photolib.settlement.SettlementStatementService.ReviewEvent;
import cn.photolib.settlement.SettlementStatementService.Statement;
import cn.photolib.settlement.SettlementStatementService.Totals;
import cn.photolib.settlement.SettlementStatementService.UploadChannel;
import cn.photolib.settlement.SettlementStatementService.UploadSource;
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
                statement, BRAND, "部长", null, NOW, Target.EMAIL).html();

        assertThat(html).contains("<p>&lt;script&gt;张三</p>");
        assertThat(html).doesNotContain("<script>");
        // 生成的片段原样进来（是一段 HTML，而不是被转义成文字）。
        assertThat(html).contains("<div style=").contains("清算周期内结束的选题里没有你的工时记录。");
    }

    @Test
    void sectionsAreDroppedWhenTheVariableIsEmptyAndUnknownPlaceholdersStay() {
        Statement statement = statement(List.of(), List.of());
        String template = "A{{#siteUrl}}<a href=\"{{siteUrl}}\">去看看</a>{{/siteUrl}}B {{notAVariable}}";

        String withoutUrl = SettlementMailRenderer.render("s", template, statement, BRAND, "部长", null, NOW, Target.EMAIL).html();
        String withUrl = SettlementMailRenderer.render("s", template, statement, BRAND, "部长",
                "https://photo.example.cn/#/", NOW, Target.EMAIL).html();

        assertThat(withoutUrl).isEqualTo("AB {{notAVariable}}");
        assertThat(withUrl).isEqualTo("A<a href=\"https://photo.example.cn/#/\">去看看</a>B {{notAVariable}}");
    }

    @Test
    void subjectIsPlainTextOnOneLine() {
        Statement statement = statement(List.of(), List.of());
        String subject = SettlementMailRenderer.render("【{{siteName}}】\r\n{{recipientName}}{{worklogTable}}", "x",
                statement, BRAND, "部长", null, NOW, Target.EMAIL).subject();

        // 主题不转义（不是 HTML）、不能带换行（会变成邮件头注入），HTML 片段在主题里为空。
        assertThat(subject).isEqualTo("【光影<工作站>】 <script>张三");
    }

    @Test
    void logoIsAnInlineAttachmentWhenSendingAndADataUriWhenPreviewing() {
        Statement statement = statement(List.of(), List.of());
        SettlementMailRenderer.Rendered sent = SettlementMailRenderer.render("s", "{{logo}}", statement, BRAND,
                "部长", null, NOW, Target.EMAIL);
        SettlementMailRenderer.Rendered preview = SettlementMailRenderer.render("s", "{{logoSrc}}", statement, BRAND,
                "部长", null, NOW, Target.PREVIEW);

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
                90, 30, "CONFIRMED", "补拍", null, LocalDateTime.of(2026, 10, 3, 14, 0), null, "王部长", "S1",
                LocalDateTime.of(2026, 10, 25, 12, 0), events);
        WorklogLine rejected = new WorklogLine(12, LocalDate.of(2026, 10, 8), "运动会", null, null, true, "现场上传",
                0, 45, "REJECTED", null, "照片不在相册里", LocalDateTime.of(2026, 10, 8, 20, 0),
                LocalDateTime.of(2026, 10, 9, 8, 0), "王部长", "S1", LocalDateTime.of(2026, 10, 25, 12, 0),
                new ArrayList<>());

        String html = SettlementMailRenderer.render("s", "{{worklogTable}}", statement(List.of(confirmed, rejected),
                List.of()), BRAND, "部长", null, NOW, Target.EMAIL).html();

        assertThat(html).contains("2026-10-03 14:20").contains("提交申报").contains("拍摄 2 小时，修图 1 小时");
        assertThat(html).contains("2026-10-04 09:05").contains("退回").contains("原因：时长&lt;填多了&gt;");
        assertThat(html).contains("2026-10-05 10:00").contains("第 2 次提交").contains("拍摄 1 小时 30 分，修图 30 分钟");
        assertThat(html).contains("2026-10-06 18:00").contains("确认").contains("审核人：王部长");
        // 没有审核记录的旧工时：至少有填报时间和当前状态，退回原因单独醒目列出。
        assertThat(html).contains("2026-10-08 20:00").contains("退回原因：</b>照片不在相册里");
        assertThat(html).contains("上传链接申报（现场上传）");
        assertThat(html).contains("工时 #11").contains("周五");
        // 所属选题的结束时间：清算单按它计入。
        assertThat(html).contains("选题：运动会（2026-10-25 结束）");
    }

    @Test
    void adoptionTableAndSummary() {
        AdoptionLine adoption = new AdoptionLine(LocalDateTime.of(2026, 10, 10, 11, 0), "校报 10 月刊", 501,
                "IMG_0001.jpg", "北校区", "编辑甲", "封面", LocalDateTime.of(2026, 10, 28, 9, 0));
        WorklogLine pending = new WorklogLine(13, LocalDate.of(2026, 10, 9), "运动会", "闭幕式", "北校区", false, null,
                30, 0, "SUBMITTED", null, null, LocalDateTime.of(2026, 10, 9, 21, 0), null, null, "S1",
                LocalDateTime.of(2026, 10, 28, 9, 0), new ArrayList<>());
        SettlementMailRenderer.Rendered rendered = SettlementMailRenderer.render("s",
                "{{summaryCards}}|{{adoptionTable}}|{{adoptedPhotos}}|{{pendingHours}}",
                statement(List.of(pending), List.of(adoption, adoption)), BRAND, "部长", null, NOW, Target.EMAIL);

        assertThat(rendered.html()).contains("#501 IMG_0001.jpg").contains("校报 10 月刊").contains("备注：封面")
                .contains("2026-10-28 结束");
        // 同一张图被引两次：张数 1、次数 2。
        assertThat(rendered.html()).contains("|1|30 分钟").contains("共被引用 2 次");
        assertThat(rendered.plainText()).contains("待审核：1 条").contains("图片 #501");
    }

    @Test
    void defaultTemplateHasNoLeftoverPlaceholders() {
        String template = new MailTemplateService(null).defaultHtml();
        String html = SettlementMailRenderer.render(MailTemplateService.DEFAULT_SUBJECT, template,
                statement(List.of(), List.of()), BRAND, "部长", "https://photo.example.cn/#/", NOW, Target.EMAIL).html();

        assertThat(html).doesNotContain("{{").contains("光影&lt;工作站&gt;").contains("cid:brand-logo")
                .contains("https://photo.example.cn/#/");
        assertThat(SettlementMailRenderer.VARIABLES).containsKeys("worklogTable", "adoptionTable", "logo",
                "projectTable", "projectCount", "uploadedPhotos");
        assertThat(template).contains("{{projectTable}}");
        assertThat(Map.copyOf(SettlementMailRenderer.VARIABLES)).hasSize(SettlementMailRenderer.VARIABLES.size());
    }

    @Test
    void photoAndProjectLinksDependOnWhereTheStatementIsShown() {
        AdoptionLine adoption = new AdoptionLine(LocalDateTime.of(2026, 10, 10, 11, 0), "校报", 501, "封面图", null,
                "编辑甲", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        ProjectLine project = new ProjectLine(77, "运动会", true, LocalDateTime.of(2026, 10, 20, 9, 0), 3,
                LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 2, 18, 0),
                List.of(new UploadSource(UploadChannel.SELF, null, null, 3)));
        Statement statement = statement(List.of(), List.of(adoption), List.of(project));
        String template = "{{projectTable}}{{adoptionTable}}";

        // 邮件没配站点地址：没有可用的绝对地址，只出文字。
        String plainMail = SettlementMailRenderer.render("s", template, statement, BRAND, "部长", null, NOW, Target.EMAIL).html();
        assertThat(plainMail).doesNotContain("<a ").contains("#501 封面图").contains("运动会");
        // 邮件配了站点地址：绝对链接，新窗口打开。
        String mail = SettlementMailRenderer.render("s", template, statement, BRAND, "部长",
                "https://photo.example.cn/#/", NOW, Target.EMAIL).html();
        assertThat(mail).contains("href=\"https://photo.example.cn/#/photos/501\" target=\"_blank\"")
                .contains("href=\"https://photo.example.cn/#/projects/77\"");
        // 预览 / PDF 和站内信：站内路径，不依赖配置。
        for (Target target : List.of(Target.PREVIEW, Target.INBOX)) {
            String html = SettlementMailRenderer.render("s", template, statement, BRAND, "部长",
                    "https://photo.example.cn/#/", NOW, target).html();
            assertThat(html).contains("href=\"/#/photos/501\"").contains("href=\"/#/projects/77\"");
        }
    }

    @Test
    void projectTableSplitsEndedAndOngoingAndShowsHowEachPhotoGotIn() {
        ProjectLine sports = new ProjectLine(77, "运动会<开幕>", true, LocalDateTime.of(2026, 10, 20, 18, 0), 12,
                LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 5, 18, 0), List.of(
                new UploadSource(UploadChannel.SELF, null, null, 5),
                new UploadSource(UploadChannel.SHARE_LINK, null, "现场<A>", 3),
                new UploadSource(UploadChannel.REQUEST_PROXY, "李负责人", null, 3),
                new UploadSource(UploadChannel.PROJECT_ADDED, null, null, 1)));
        ProjectLine paper = new ProjectLine(78, "校报", false, null, 1,
                LocalDateTime.of(2026, 10, 9, 9, 0), LocalDateTime.of(2026, 10, 9, 9, 0), List.of(
                new UploadSource(UploadChannel.SHARE_LINK, null, null, 1)));
        ProjectLine later = new ProjectLine(79, "晚会", false, LocalDateTime.of(2026, 11, 2, 9, 0), 2,
                LocalDateTime.of(2026, 10, 12, 9, 0), LocalDateTime.of(2026, 10, 13, 9, 0), List.of(
                new UploadSource(UploadChannel.PROJECT_ADDED, "采编丙", null, 2)));
        SettlementMailRenderer.Rendered rendered = SettlementMailRenderer.render("s",
                "{{projectTable}}|{{projectCount}}|{{uploadedPhotos}}|{{ongoingProjectCount}}|{{ongoingPhotos}}",
                statement(List.of(), List.of(), List.of(sports, paper, later)), BRAND, "部长", null, NOW, Target.PREVIEW);

        String html = rendered.html();
        assertThat(html).contains("本期结束 <b style=\"color:#1f3b57;\">1</b> 个参与的选题")
                .contains("另有 <b style=\"color:#b26a00;\">2</b> 个参与的选题尚未结束（已上传 3 张）")
                .endsWith("|1|12|2|3");
        // 两组分开，已结束的在前。
        assertThat(html.indexOf("已结束的选题 · 计入本期清算")).isPositive()
                .isLessThan(html.indexOf("未结束的选题 · 不计入本期"));
        assertThat(html).contains("运动会&lt;开幕&gt;").contains("<b>12</b> 张").contains("2026-10-02 至 2026-10-05 上传")
                .contains(">2026-10-20<");
        // 上传方式：本人 / 上传链接 / 代传 / 添加到选题，名字和链接名都转义。
        assertThat(html).contains("本人上传 <b>5</b> 张").contains("通过上传链接「现场&lt;A&gt;」上传 <b>3</b> 张")
                .contains("李负责人 代传（需求交付） <b>3</b> 张").contains("他人 添加到选题 <b>1</b> 张")
                .contains("通过上传链接上传 <b>1</b> 张").contains("采编丙 添加到选题 <b>2</b> 张");
        assertThat(html).contains(">进行中<").contains("2026-11-02 结束（晚于本期）");
        assertThat(rendered.plainText())
                .contains("本期结束的参与选题：1 个，上传图片 12 张")
                .contains("尚未结束的参与选题：2 个，已上传图片 3 张（不计入本期）")
                .contains("- [已结束] 运动会<开幕>（2026-10-20）：上传 12 张，本人上传 5 张；通过上传链接「现场<A>」上传 3 张；"
                        + "李负责人 代传（需求交付） 3 张；他人 添加到选题 1 张")
                .contains("- [未结束] 校报（进行中）：上传 1 张，通过上传链接上传 1 张");
        assertThat(SettlementMailRenderer.render("s", "{{projectTable}}", statement(List.of(), List.of()), BRAND,
                "部长", null, NOW, Target.PREVIEW).html()).contains("清算周期内没有参与的选题。");
    }

    @Test
    void onlyOngoingProjectsStillListThem() {
        ProjectLine paper = new ProjectLine(78, "校报", false, null, 4,
                LocalDateTime.of(2026, 10, 9, 9, 0), LocalDateTime.of(2026, 10, 9, 9, 0), List.of(
                new UploadSource(UploadChannel.SELF, null, null, 4)));
        String html = SettlementMailRenderer.render("s", "{{projectTable}}",
                statement(List.of(), List.of(), List.of(paper)), BRAND, "部长", null, NOW, Target.PREVIEW).html();

        assertThat(html).contains("本期结束 <b style=\"color:#1f3b57;\">0</b> 个参与的选题")
                .contains("未结束的选题 · 不计入本期").doesNotContain("已结束的选题 · 计入本期清算");
    }

    @Test
    void inboxSnapshotKeepsTheLogoAsAReferenceAndDropsTheLoginButton() {
        Statement statement = statement(List.of(), List.of());
        SettlementMailRenderer.Rendered inbox = SettlementMailRenderer.render("s",
                "{{logo}}{{#siteUrl}}<a href=\"{{siteUrl}}\">登录</a>{{/siteUrl}}", statement, BRAND, "部长",
                "https://photo.example.cn/#/", NOW, Target.INBOX);

        assertThat(inbox.html()).contains("src=\"cid:brand-logo\"").doesNotContain("登录");
        assertThat(inbox.inlineImages()).isEmpty();
        assertThat(SettlementMailRenderer.inlineLogo(inbox.html(), BRAND))
                .contains("src=\"data:image/png;base64,AQID\"").doesNotContain("cid:");
    }

    private static Statement statement(List<WorklogLine> worklogs, List<AdoptionLine> adoptions) {
        return statement(worklogs, adoptions, List.of());
    }

    private static Statement statement(List<WorklogLine> worklogs, List<AdoptionLine> adoptions,
                                       List<ProjectLine> projects) {
        Recipient recipient = new Recipient(1, "zhangsan", "<script>张三", "z@example.cn", "北校区", "成员",
                worklogs.size(), 0, 0, 0, 0, adoptions.size(), 0, 0, projects.size(), 0, 0);
        return new Statement(recipient, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), projects, worklogs,
                adoptions, Totals.of(worklogs, adoptions, projects));
    }
}

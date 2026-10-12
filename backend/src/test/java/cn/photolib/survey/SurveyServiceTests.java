package cn.photolib.survey;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.form.FormFileService;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.survey.model.SurveyStatus;
import cn.photolib.user.model.UserRole;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SurveyServiceTests {
    private static final long ADMIN_ID = 9_811L;
    private static final long EAST_MEMBER_ID = 9_812L;
    private static final long WEST_MEMBER_ID = 9_813L;
    private static final long NO_ACCESS_ID = 9_814L;
    private static final long EAST_CAMPUS = 9_881L;
    private static final long WEST_CAMPUS = 9_882L;

    @Autowired private SurveyService surveys;
    @Autowired private SurveyResponseService responses;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;
    @Autowired private SqlSessionTemplate sqlSession;

    private AuthenticatedUser admin;
    private AuthenticatedUser eastMember;
    private AuthenticatedUser westMember;

    @BeforeEach
    void setUp() {
        campus(EAST_CAMPUS, "SURVEY-E", "问卷东校区");
        campus(WEST_CAMPUS, "SURVEY-W", "问卷西校区");
        user(ADMIN_ID, "survey-admin", "问卷管理员", "ADMIN", null);
        user(EAST_MEMBER_ID, "survey-east", "东区成员", "CAMPUS_MANAGER", null);
        user(WEST_MEMBER_ID, "survey-west", "西区成员", "CAMPUS_MANAGER", null);
        Long noAccessGroup = jdbc.sql("SELECT id FROM permission_group WHERE code='NO_ACCESS'")
                .query(Long.class).single();
        user(NO_ACCESS_ID, "survey-none", "无权限成员", "CAMPUS_MANAGER", noAccessGroup);
        grantCampus(EAST_MEMBER_ID, EAST_CAMPUS);
        grantCampus(WEST_MEMBER_ID, WEST_CAMPUS);

        admin = new AuthenticatedUser(ADMIN_ID, "survey-admin", "问卷管理员", UserRole.ADMIN, null, false);
        eastMember = new AuthenticatedUser(EAST_MEMBER_ID, "survey-east", "东区成员",
                UserRole.CAMPUS_MANAGER, EAST_CAMPUS, false);
        westMember = new AuthenticatedUser(WEST_MEMBER_ID, "survey-west", "西区成员",
                UserRole.CAMPUS_MANAGER, WEST_CAMPUS, false);
    }

    @Test
    void audienceOnlyOffersEnabledAccountsHoldingSurveyAccessWithGroupAndCampusFilters() {
        var options = surveys.audienceOptions(admin);

        assertThat(options.candidates()).extracting(SurveyService.AudienceCandidate::id)
                .contains(ADMIN_ID, EAST_MEMBER_ID, WEST_MEMBER_ID)
                .doesNotContain(NO_ACCESS_ID);
        var east = options.candidates().stream().filter(c -> c.id() == EAST_MEMBER_ID).findFirst().orElseThrow();
        assertThat(east.campusIds()).containsExactly(EAST_CAMPUS);
        assertThat(east.permissionGroupName()).isEqualTo("校区负责人");
        assertThat(options.campuses()).extracting(SurveyService.Option::name).contains("问卷东校区", "问卷西校区");
        assertThat(options.permissionGroups()).extracting(SurveyService.Option::name).contains("校区负责人");

        // 只能发给有访问权限的人：后端不信任前端的候选名单。
        assertThatThrownBy(() -> surveys.create(command(List.of(NO_ACCESS_ID)), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有问卷访问权限");
    }

    @Test
    void campusScopedCreatorOnlySeesAndTargetsPeopleSharingTheirCampus() {
        AuthenticatedUser scopedCreator = campusUser(EAST_MEMBER_ID, EAST_CAMPUS,
                Set.of(PermissionCode.SURVEY_CREATE, PermissionCode.SURVEY_ACCESS));

        assertThat(surveys.audienceOptions(scopedCreator).candidates())
                .extracting(SurveyService.AudienceCandidate::id)
                .contains(EAST_MEMBER_ID).doesNotContain(WEST_MEMBER_ID, ADMIN_ID);
        assertThatThrownBy(() -> surveys.create(command(List.of(WEST_MEMBER_ID)), scopedCreator))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不在你能选择的范围内");
        assertThat(surveys.create(command(List.of(EAST_MEMBER_ID)), scopedCreator).targetCount()).isOne();
    }

    @Test
    void lifecyclePublishNotifiesTargetsFreezesQuestionsAndCloseStopsSubmissions() {
        var draft = surveys.create(command(List.of(EAST_MEMBER_ID)), admin);
        assertThat(draft.status()).isEqualTo(SurveyStatus.DRAFT);
        assertThat(draft.targetUserIds()).containsExactly(EAST_MEMBER_ID);
        // 草稿对填写人来说不存在。
        assertThat(responses.assigned(eastMember)).extracting(SurveyResponseService.AssignedSurvey::id)
                .doesNotContain(draft.id());
        assertNotFound(() -> responses.fillView(draft.id(), eastMember));

        var published = surveys.publish(draft.id(), draft.version(), admin);
        assertThat(published.status()).isEqualTo(SurveyStatus.PUBLISHED);
        assertThat(published.open()).isTrue();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id=:userId AND event_type='SURVEY_PUBLISHED' AND action_url=:url
                """).param("userId", EAST_MEMBER_ID).param("url", "/surveys/" + draft.id() + "/fill")
                .query(Long.class).single()).isOne();

        var changed = new SurveyService.SurveyCommand("改名后的问卷", "新描述", "## 新简介",
                new RecruitmentFormSchema(List.of(new RecruitmentFormSchema.Field("other",
                        RecruitmentFieldType.SHORT_TEXT, "别的题", null, null, false, List.of()))),
                null, List.of(EAST_MEMBER_ID));
        assertThatThrownBy(() -> surveys.update(draft.id(), changed, published.version(), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("题目不能再改");

        // 题目不变，补发给西区成员：只有新加的人收到通知。
        var sameQuestions = new SurveyService.SurveyCommand("改名后的问卷", "新描述", "## 新简介",
                schema(), null, List.of(EAST_MEMBER_ID, WEST_MEMBER_ID));
        var updated = surveys.update(draft.id(), sameQuestions, published.version(), admin);
        assertThat(updated.title()).isEqualTo("改名后的问卷");
        assertThat(updated.targetCount()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM user_notification WHERE event_type='SURVEY_PUBLISHED' AND user_id IN (:ids)
                """).param("ids", List.of(EAST_MEMBER_ID, WEST_MEMBER_ID)).query(Long.class).single())
                .isEqualTo(2);

        var closed = surveys.close(draft.id(), updated.version(), admin);
        assertThat(closed.status()).isEqualTo(SurveyStatus.CLOSED);
        assertThatThrownBy(() -> responses.submit(draft.id(), validAnswers(), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经结束");
        // 结束后仍能看到这份问卷（只是不能再交）。
        assertThat(responses.fillView(draft.id(), eastMember).open()).isFalse();
    }

    @Test
    void onlyTargetsWithAccessCanSubmitAndEachPersonSubmitsOnce() {
        var survey = publish(List.of(EAST_MEMBER_ID));

        assertNotFound(() -> responses.fillView(survey.id(), westMember));
        assertNotFound(() -> responses.submit(survey.id(), validAnswers(), westMember));
        AuthenticatedUser withoutAccess = campusUser(EAST_MEMBER_ID, EAST_CAMPUS, Set.of(PermissionCode.PHOTO_VIEW));
        assertThatThrownBy(() -> responses.fillView(survey.id(), withoutAccess))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ErrorCode.FORBIDDEN);

        assertThatThrownBy(() -> responses.submit(survey.id(), Map.of("rating", "非常满意"), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不是有效选项");
        assertThatThrownBy(() -> responses.submit(survey.id(), Map.of("topics", List.of("器材")), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必填");

        var receipt = responses.submit(survey.id(), validAnswers(), eastMember);
        assertThat(receipt.responseId()).isNotNull();
        var fill = responses.fillView(survey.id(), eastMember);
        assertThat(fill.myResponse()).isNotNull();
        assertThat(fill.myResponse().answers()).containsEntry("rating", "满意");
        assertThat(responses.assigned(eastMember)).filteredOn(item -> item.id().equals(survey.id()))
                .singleElement().satisfies(item -> assertThat(item.submittedAt()).isNotNull());

        assertThatThrownBy(() -> responses.submit(survey.id(), validAnswers(), eastMember))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void resultsNeedResultPermissionAndCampusScopedViewersSeeOnlyTheirCampus() throws Exception {
        var survey = publish(List.of(EAST_MEMBER_ID, WEST_MEMBER_ID));
        responses.submit(survey.id(), validAnswers(), eastMember);
        responses.submit(survey.id(), Map.of("rating", "一般", "topics", List.of("器材", "后期"),
                "note", "=HYPERLINK(\"x\")"), westMember);

        // 能填不等于能看结果。
        assertThatThrownBy(() -> responses.list(survey.id(), 1, 20, null, eastMember))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(responses.list(survey.id(), 1, 20, null, admin).total()).isEqualTo(2);
        assertThat(responses.list(survey.id(), 1, 20, "东区", admin).items())
                .extracting(SurveyResponseService.ResponseSummary::userId).containsExactly(EAST_MEMBER_ID);

        AuthenticatedUser eastViewer = campusUser(9_815L, EAST_CAMPUS, Set.of(PermissionCode.SURVEY_RESULT_VIEW));
        assertThat(responses.list(survey.id(), 1, 20, null, eastViewer).items())
                .extracting(SurveyResponseService.ResponseSummary::userId).containsExactly(EAST_MEMBER_ID);
        assertThat(responses.targets(survey.id(), eastViewer))
                .extracting(SurveyResponseService.TargetStatus::userId).containsExactly(EAST_MEMBER_ID);
        Long westResponse = responses.list(survey.id(), 1, 20, "西区", admin).items().getFirst().id();
        assertNotFound(() -> responses.get(westResponse, eastViewer));
        assertThat(responses.summary(survey.id(), eastViewer).responseCount()).isOne();

        var summary = responses.summary(survey.id(), admin);
        assertThat(summary.responseCount()).isEqualTo(2);
        var rating = summary.fields().stream().filter(field -> field.fieldId().equals("rating")).findFirst().orElseThrow();
        assertThat(rating.options()).extracting(SurveyResponseService.OptionCount::count).containsExactly(1L, 1L, 0L);
        var topics = summary.fields().stream().filter(field -> field.fieldId().equals("topics")).findFirst().orElseThrow();
        assertThat(topics.answeredCount()).isEqualTo(2);
        assertThat(topics.options()).extracting(SurveyResponseService.OptionCount::count).containsExactly(2L, 1L);

        var detail = responses.get(westResponse, admin);
        assertThat(detail.displayName()).isEqualTo("西区成员");
        assertThat(detail.answers()).containsEntry("rating", "一般");
    }

    @Test
    void excelExportHasResponsesPendingPeopleAndChoiceStatistics() throws Exception {
        var survey = publish(List.of(EAST_MEMBER_ID, WEST_MEMBER_ID));
        responses.submit(survey.id(), Map.of("rating", "满意", "topics", List.of("器材", "后期"),
                "note", "=1+1"), eastMember);

        var export = responses.export(survey.id(), admin);
        assertThat(export.fileName()).startsWith("摄影部满意度调查-问卷结果-").endsWith(".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
            List<List<String>> answers = rows(workbook.getSheet("答卷"));
            assertThat(answers.getFirst()).containsExactly("姓名", "账号", "权限组", "提交时间", "修改时间",
                    "整体满意度", "想学的内容", "其他建议", "附件");
            assertThat(answers.get(1)).startsWith("东区成员", "survey-east", "校区负责人");
            // 交了之后没改过：修改时间留空。
            assertThat(answers.get(1).get(4)).isEmpty();
            assertThat(answers.get(1).get(5)).isEqualTo("满意");
            assertThat(answers.get(1).get(6)).isEqualTo("器材，后期");
            // 公式注入：以 = 开头的答案不能被 Excel 当成公式执行。
            assertThat(answers.get(1).get(7)).isEqualTo("'=1+1");

            assertThat(rows(workbook.getSheet("未提交"))).hasSize(2)
                    .last().satisfies(row -> assertThat(row).startsWith("西区成员", "survey-west"));
            List<List<String>> stats = rows(workbook.getSheet("选择题统计"));
            assertThat(stats).contains(List.of("整体满意度", "满意", "1", "100.0%"),
                    List.of("想学的内容", "后期", "1", "100.0%"));
        }
    }

    @Test
    void fileQuestionUploadsAttachOnSubmitAndCannotBeReused() {
        var survey = publish(List.of(EAST_MEMBER_ID, WEST_MEMBER_ID));
        var ticket = responses.createFileTicket(survey.id(),
                new FormFileService.TicketRequest("attachment", "作业.pdf", "application/pdf", 5L), eastMember);
        assertThat(ticket.contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> responses.createFileTicket(survey.id(),
                new FormFileService.TicketRequest("note", "x.pdf", "application/pdf", 5L), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能上传文件");

        Map<String, Object> answers = new java.util.HashMap<>(validAnswers());
        answers.put("attachment", List.of(ticket.fileId()));
        // 文件还没真正传上去就提交：服务端要核对对象存储，而不是信任前端。
        assertThatThrownBy(() -> responses.submit(survey.id(), answers, eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("还没有上传完整");

        putObject(ticket.fileId(), "hello");
        // 别人不能拿我的文件 id 交卷。
        assertThatThrownBy(() -> responses.submit(survey.id(), answers, westMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("找不到");

        responses.submit(survey.id(), answers, eastMember);
        var mine = responses.fillView(survey.id(), eastMember).myResponse();
        assertThat(mine.files()).singleElement().satisfies(file -> {
            assertThat(file.fileName()).isEqualTo("作业.pdf");
            assertThat(file.downloadUrl()).isNotBlank();
            assertThat(file.previewUrl()).isNull();
        });
        @SuppressWarnings("unchecked")
        var stored = (List<Map<String, Object>>) mine.answers().get("attachment");
        assertThat(stored).singleElement().satisfies(file -> assertThat(file).containsEntry("fileName", "作业.pdf"));
    }

    @Test
    void aSubmittedResponseCanBeRevisedWhileTheSurveyIsOpenAndKeepsItsSubmissionTime() throws Exception {
        var survey = publish(List.of(EAST_MEMBER_ID, WEST_MEMBER_ID));

        // 没交过的人没有答卷可改；改答卷也不能绕开发放名单。
        assertNotFound(() -> responses.revise(survey.id(), validAnswers(), eastMember));
        assertNotFound(() -> responses.revise(survey.id(), validAnswers(), admin));

        var receipt = responses.submit(survey.id(), validAnswers(), eastMember);
        assertThat(receipt.modifiedAt()).isNull();
        var submitted = responses.fillView(survey.id(), eastMember).myResponse();
        assertThat(submitted.modifiedAt()).isNull();

        // 修改和第一次提交走同一套校验。
        assertThatThrownBy(() -> responses.revise(survey.id(), Map.of("topics", List.of("器材")), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必填");
        assertThatThrownBy(() -> responses.revise(survey.id(), Map.of("rating", "非常满意"), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不是有效选项");

        var revised = responses.revise(survey.id(),
                Map.of("rating", "不满意", "topics", List.of("后期"), "note", "改主意了"), eastMember);
        assertThat(revised.responseId()).isEqualTo(receipt.responseId());
        assertThat(revised.modifiedAt()).isNotNull();

        var mine = responses.fillView(survey.id(), eastMember).myResponse();
        assertThat(mine.id()).isEqualTo(receipt.responseId());
        assertThat(mine.answers()).containsEntry("rating", "不满意").containsEntry("note", "改主意了");
        // 提交时间是「什么时候交的」，改答卷不动它。和库里改之前的值比，而不是和回执比：
        // 回执带着 JVM 的纳秒，落库只到微秒。
        assertThat(mine.submittedAt()).isEqualTo(submitted.submittedAt());
        assertThat(mine.modifiedAt()).isNotNull();

        // 还是同一份答卷：结果里不会多出一份，统计按改后的答案算。
        var listed = responses.list(survey.id(), 1, 20, null, admin);
        assertThat(listed.total()).isOne();
        assertThat(listed.items().getFirst().modifiedAt()).isNotNull();
        assertThat(responses.get(receipt.responseId(), admin).answers()).containsEntry("rating", "不满意");
        var rating = responses.summary(survey.id(), admin).fields().stream()
                .filter(field -> field.fieldId().equals("rating")).findFirst().orElseThrow();
        assertThat(rating.options()).extracting(SurveyResponseService.OptionCount::count).containsExactly(0L, 0L, 1L);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                responses.export(survey.id(), admin).content()))) {
            List<String> row = rows(workbook.getSheet("答卷")).get(1);
            assertThat(row.get(4)).isNotEmpty();
            assertThat(row.get(5)).isEqualTo("不满意");
        }

        // 交过之后再走一次「提交」仍然是重复提交，不会悄悄覆盖。
        assertThatThrownBy(() -> responses.submit(survey.id(), validAnswers(), eastMember))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void responsesAreFrozenOnceTheDeadlinePassesOrTheSurveyIsClosed() {
        var timed = publish(List.of(EAST_MEMBER_ID));
        responses.submit(timed.id(), validAnswers(), eastMember);
        responses.revise(timed.id(), Map.of("rating", "一般"), eastMember);

        // 过了截止时间：问卷状态还是 PUBLISHED，但答卷已经不能再改，文件也不能再传。
        passDeadline(timed.id());
        assertThatThrownBy(() -> responses.revise(timed.id(), Map.of("rating", "不满意"), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能再修改");
        assertThatThrownBy(() -> responses.createFileTicket(timed.id(),
                new FormFileService.TicketRequest("attachment", "晚了.pdf", "application/pdf", 5L), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经结束");
        var afterDeadline = responses.fillView(timed.id(), eastMember);
        assertThat(afterDeadline.open()).isFalse();
        assertThat(afterDeadline.myResponse().answers()).containsEntry("rating", "一般");

        // 不设截止时间的问卷一直可以改，直到发起人手动结束。
        var draft = surveys.create(new SurveyService.SurveyCommand("不限时问卷", null, null, schema(), null,
                List.of(EAST_MEMBER_ID)), admin);
        var open = surveys.publish(draft.id(), draft.version(), admin);
        responses.submit(open.id(), validAnswers(), eastMember);
        responses.revise(open.id(), Map.of("rating", "一般"), eastMember);
        surveys.close(open.id(), open.version(), admin);
        assertThatThrownBy(() -> responses.revise(open.id(), Map.of("rating", "不满意"), eastMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能再修改");
        assertThat(responses.fillView(open.id(), eastMember).myResponse().answers())
                .containsEntry("rating", "一般");
    }

    @Test
    void revisingKeepsUntouchedFilesAttachesNewOnesAndReleasesRemovedOnes() {
        var survey = publish(List.of(EAST_MEMBER_ID, WEST_MEMBER_ID));
        String first = uploadedFile(survey.id(), "第一版.pdf", eastMember);
        responses.submit(survey.id(), withFiles(first), eastMember);

        // 交过之后还能再传文件，否则改答卷时没法换附件。
        String second = uploadedFile(survey.id(), "补充.pdf", eastMember);
        responses.revise(survey.id(), withFiles(first, second), eastMember);
        assertThat(responses.fillView(survey.id(), eastMember).myResponse().files())
                .extracting(FormFileService.FileView::fileName).containsExactly("第一版.pdf", "补充.pdf");
        assertThat(fileStatus(first)).isEqualTo("ATTACHED");
        assertThat(fileStatus(second)).isEqualTo("ATTACHED");

        // 拿掉第一个：它退回 PENDING 等清理任务收走，答卷里和下载地址里都不再有它。
        responses.revise(survey.id(), withFiles(second), eastMember);
        assertThat(responses.fillView(survey.id(), eastMember).myResponse().files())
                .extracting(FormFileService.FileView::fileName).containsExactly("补充.pdf");
        assertThat(fileStatus(first)).isEqualTo("PENDING");
        assertThat(fileStatus(second)).isEqualTo("ATTACHED");

        // 别人答卷里的文件不能借改答卷的机会挂到自己名下。
        responses.submit(survey.id(), validAnswers(), westMember);
        assertThatThrownBy(() -> responses.revise(survey.id(), withFiles(second), westMember))
                .isInstanceOf(BusinessException.class).hasMessageContaining("找不到");
        assertThat(fileStatus(second)).isEqualTo("ATTACHED");

        // 全部拿掉也可以（这道题不是必填）。
        responses.revise(survey.id(), validAnswers(), eastMember);
        assertThat(responses.fillView(survey.id(), eastMember).myResponse().files()).isEmpty();
        assertThat(fileStatus(second)).isEqualTo("PENDING");
    }

    @Test
    void onlyDraftsCanBeDeletedAndPublishingNeedsTargets() {
        var empty = surveys.create(command(List.of()), admin);
        assertThatThrownBy(() -> surveys.publish(empty.id(), empty.version(), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("还没有选发给谁");
        surveys.delete(empty.id(), empty.version(), admin);
        assertNotFound(() -> surveys.get(empty.id(), admin));

        var published = publish(List.of(EAST_MEMBER_ID));
        assertThatThrownBy(() -> surveys.delete(published.id(), published.version(), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有草稿");
    }

    // ------------------------------------------------------------------

    private SurveyService.SurveyView publish(List<Long> targets) {
        var draft = surveys.create(command(targets), admin);
        return surveys.publish(draft.id(), draft.version(), admin);
    }

    private SurveyService.SurveyCommand command(List<Long> targets) {
        return new SurveyService.SurveyCommand("摄影部满意度调查", "花两分钟说说你的想法",
                "## 说明\n\n![图](/api/v1/description-images/X)", schema(),
                LocalDateTime.now(clock).plusDays(3), targets);
    }

    private static RecruitmentFormSchema schema() {
        return new RecruitmentFormSchema(List.of(
                new RecruitmentFormSchema.Field("rating", RecruitmentFieldType.SINGLE_CHOICE, "整体满意度",
                        null, null, true, List.of("满意", "一般", "不满意")),
                new RecruitmentFormSchema.Field("topics", RecruitmentFieldType.MULTIPLE_CHOICE, "想学的内容",
                        null, null, false, List.of("器材", "后期")),
                new RecruitmentFormSchema.Field("note", RecruitmentFieldType.LONG_TEXT, "其他建议",
                        null, null, false, List.of()),
                new RecruitmentFormSchema.Field("attachment", RecruitmentFieldType.FILE_UPLOAD, "附件",
                        null, null, false, List.of())));
    }

    private static Map<String, Object> validAnswers() {
        return Map.of("rating", "满意", "topics", List.of("器材"));
    }

    /** 申请一张票据并把文件真的传上去，返回文件 id。 */
    private String uploadedFile(long surveyId, String fileName, AuthenticatedUser user) {
        var ticket = responses.createFileTicket(surveyId,
                new FormFileService.TicketRequest("attachment", fileName, "application/pdf", 5L), user);
        putObject(ticket.fileId(), "hello");
        return ticket.fileId();
    }

    private static Map<String, Object> withFiles(String... fileIds) {
        Map<String, Object> answers = new java.util.HashMap<>(validAnswers());
        answers.put("attachment", List.of(fileIds));
        return answers;
    }

    private String fileStatus(String fileId) {
        return jdbc.sql("SELECT status FROM form_file_upload WHERE id=:id")
                .param("id", fileId).query(String.class).single();
    }

    /**
     * 把截止时间拨到过去。直接改库绕过了 MyBatis，同一个事务里它的一级缓存还留着改之前的问卷行，
     * 得清掉，否则服务读到的仍是没过期的那一份。
     */
    private void passDeadline(long surveyId) {
        jdbc.sql("UPDATE survey SET ends_at=:endsAt WHERE id=:id")
                .param("endsAt", LocalDateTime.now(clock).minusMinutes(1)).param("id", surveyId).update();
        sqlSession.clearCache();
    }

    private void putObject(String fileId, String content) {
        String objectKey = jdbc.sql("SELECT object_key FROM form_file_upload WHERE id=:id")
                .param("id", fileId).query(String.class).single();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
    }

    private static AuthenticatedUser campusUser(long id, long campusId, Set<PermissionCode> permissions) {
        return new AuthenticatedUser(id, "scoped-" + id, "校区用户", UserRole.CAMPUS_MANAGER, campusId, false,
                -1L, "CUSTOM", "自定义", DataScope.CAMPUS, permissions, Set.of(campusId));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static List<List<String>> rows(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < row.getLastCellNum(); column++) {
                Cell cell = row.getCell(column);
                cells.add(cell == null ? "" : switch (cell.getCellType()) {
                    case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                    default -> cell.getStringCellValue();
                });
            }
            rows.add(cells);
        }
        return rows;
    }

    private void campus(long id, String code, String name) {
        jdbc.sql("INSERT INTO campus(id, code, name, enabled) VALUES (:id, :code, :name, TRUE)")
                .param("id", id).param("code", code).param("name", name).update();
    }

    private void user(long id, String username, String name, String role, Long groupId) {
        jdbc.sql("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, permission_group_id,
                                      enabled, must_change_password)
                VALUES (:id, :username, 'hash', :name, :role, :groupId, TRUE, FALSE)
                """).param("id", id).param("username", username).param("name", name).param("role", role)
                .param("groupId", groupId).update();
    }

    private void grantCampus(long userId, long campusId) {
        jdbc.sql("INSERT INTO user_campus_permission(user_id, campus_id) VALUES (:userId, :campusId)")
                .param("userId", userId).param("campusId", campusId).update();
    }
}

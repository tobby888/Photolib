package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.recruitment.mapper.RecruitmentApplicationMapper;
import cn.photolib.recruitment.mapper.RecruitmentTaskMapper;
import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.recruitment.model.RecruitmentTaskStatus;
import cn.photolib.permission.DataScope;
import cn.photolib.user.model.UserRole;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RecruitmentCoreTests {
    private static final long ADMIN_ID = 9_701L;

    @Autowired private RecruitmentTaskService tasks;
    @Autowired private RecruitmentDraftService drafts;
    @Autowired private RecruitmentApplicationService applications;
    @Autowired private RecruitmentFormSchemaValidator schemas;
    @Autowired private RecruitmentTaskMapper taskMapper;
    @Autowired private RecruitmentApplicationMapper applicationMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;
    @Autowired private RecruitmentPublicController publicController;
    @Autowired private AnonymousRecruitmentRateLimiter sharedLimiter;

    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'recruitment-test-admin', 'hash', '招募测试管理员',
                        'ADMIN', TRUE, FALSE)
                """).param("id", ADMIN_ID).update();
        admin = new AuthenticatedUser(ADMIN_ID, "recruitment-test-admin", "招募测试管理员",
                UserRole.ADMIN, null, false);
    }

    @Test
    void taskLifecycleUsesDraftPublishedClosedTransitionsAndOptimisticVersions() {
        var created = tasks.create(command(false), admin);
        assertThat(created.status()).isEqualTo(RecruitmentTaskStatus.DRAFT);
        assertThat(created.publicId()).hasSize(26);
        assertThat(tasks.active()).isEmpty();

        var published = tasks.publish(created.id(), created.version(), admin);
        assertThat(published.status()).isEqualTo(RecruitmentTaskStatus.PUBLISHED);
        assertThat(published.publishedBy()).isEqualTo(ADMIN_ID);
        assertThat(published.publishedAt()).isNotNull();
        assertThat(tasks.active()).extracting(RecruitmentTaskService.PublicTaskView::publicId)
                .contains(created.publicId());

        assertThatThrownBy(() -> tasks.publish(created.id(), published.version(), admin))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);

        var closed = tasks.close(created.id(), published.version(), admin);
        assertThat(closed.status()).isEqualTo(RecruitmentTaskStatus.CLOSED);
        assertThat(closed.closedBy()).isEqualTo(ADMIN_ID);
        assertThat(tasks.active()).isEmpty();
    }

    @Test
    void activeWindowIsHalfOpenAtBothDatabaseBoundaries() {
        var created = tasks.create(command(false), admin);
        tasks.publish(created.id(), created.version(), admin);
        LocalDateTime boundary = LocalDateTime.now(clock).withNano(123_456_000);

        jdbc.sql("UPDATE recruitment_task SET starts_at=:at, ends_at=:end WHERE id=:id")
                .param("at", boundary).param("end", boundary.plusHours(1)).param("id", created.id()).update();
        assertThat(taskMapper.findActive(boundary))
                .extracting(task -> task.getId()).contains(created.id());

        assertThat(taskMapper.findActive(boundary.plusHours(1)))
                .extracting(task -> task.getId()).doesNotContain(created.id());
    }

    @Test
    void publishedTaskFreezesSchemaStudentAndUploadConfigurationButAllowsMetadataEdit() {
        var original = command(false);
        var created = tasks.create(original, admin);
        var published = tasks.publish(created.id(), created.version(), admin);

        var renamed = new RecruitmentTaskService.TaskCommand(
                "2026 秋季招募（更新）", "新的介绍", original.formSchema(),
                original.studentIdLabel(), original.studentIdHelp(), original.uploadLabel(),
                original.uploadHelp(), original.uploadRequired(), original.startsAt(), original.endsAt());
        var updated = tasks.update(created.id(), renamed, published.version(), admin);
        assertThat(updated.title()).contains("更新");

        var changedField = new RecruitmentFormSchema.Field("motivation", RecruitmentFieldType.LONG_TEXT,
                "报名理由", "请认真填写", "至少 100 字", true, List.of());
        var changed = new RecruitmentTaskService.TaskCommand(
                renamed.title(), renamed.introMarkdown(), new RecruitmentFormSchema(List.of(changedField)),
                renamed.studentIdLabel(), renamed.studentIdHelp(), renamed.uploadLabel(),
                renamed.uploadHelp(), renamed.uploadRequired(), renamed.startsAt(), renamed.endsAt());
        assertThatThrownBy(() -> tasks.update(created.id(), changed, updated.version(), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发布后表单结构");
    }

    @Test
    void campusManagerCanViewButCannotPublishTasksAtServiceBoundary() {
        var manager = new AuthenticatedUser(9_702L, "manager", "负责人",
                UserRole.CAMPUS_MANAGER, 1L, false);
        assertThat(tasks.list(1, 20, null, null, manager).items()).isEmpty();
        assertThatThrownBy(() -> tasks.create(command(false), manager))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void draftStoresOnlyTokenHashAndRejectsWrongTokenAndClosedWindow() {
        var task = publish(false);
        var ticket = drafts.create(task.publicId(), " ２０２３ ００１a ");
        String storedHash = jdbc.sql("SELECT token_hash FROM recruitment_draft WHERE id=:id")
                .param("id", ticket.draftId()).query(String.class).single();
        assertThat(storedHash).hasSize(64).isNotEqualTo(ticket.draftToken());
        assertThat(jdbc.sql("SELECT normalized_student_id FROM recruitment_draft WHERE id=:id")
                .param("id", ticket.draftId()).query(String.class).single()).isEqualTo("2023001A");

        assertThatThrownBy(() -> drafts.requireWritable(task.publicId(), ticket.draftId(), "wrong"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(drafts.requireWritable(task.publicId(), ticket.draftId(), ticket.draftToken()).getId())
                .isEqualTo(ticket.draftId());

        tasks.close(task.id(), task.version(), admin);
        assertThatThrownBy(() -> drafts.requireWritable(
                task.publicId(), ticket.draftId(), ticket.draftToken()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("失效");
    }

    @Test
    void nfkcWhitespaceAndCaseNormalizationEnforcesOneSubmissionPerStudent() {
        var task = publish(false);
        var first = drafts.create(task.publicId(), "２０２３ ００１a");
        var second = drafts.create(task.publicId(), "2023001A");

        var receipt = applications.submit(task.publicId(), first.draftId(), first.draftToken(),
                "２０２３\u00A0００１a", validAnswers());
        assertThat(receipt.applicationId()).hasSize(26);
        assertThat(applicationMapper.countByStudent(task.id(), "2023001A")).isOne();

        assertThatThrownBy(() -> applications.submit(task.publicId(), second.draftId(),
                second.draftToken(), "2023001a", validAnswers()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    assertThat(business).hasMessage("该学号已提交过本次招募，请勿重复提交");
                });
        // Draft creation intentionally does not reveal whether this identifier
        // already submitted. Uniqueness is enforced only at final submission;
        // this is an input identifier rule, not student identity authentication.
        assertThat(drafts.create(task.publicId(), "２０２３００１ａ").draftId()).hasSize(26);
        assertThatThrownBy(() -> drafts.create(task.publicId(), "2023-001A!"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("学号只能包含");
    }

    @Test
    void finalSubmissionCannotRebindDraftToAnotherStudentIdentifier() {
        var task = publish(false);
        var ticket = drafts.create(task.publicId(), "BOUND-001");

        assertThatThrownBy(() -> applications.submit(task.publicId(), ticket.draftId(),
                ticket.draftToken(), "BOUND-002", validAnswers()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(business).hasMessageContaining("创建申请草稿时填写的学号一致");
                });
        assertThat(applicationMapper.countByTask(task.id(), null)).isZero();
        assertThat(jdbc.sql("SELECT status FROM recruitment_draft WHERE id=:id")
                .param("id", ticket.draftId()).query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void publishedDeadlineChangesSynchronizeOpenDraftsAndCloseExpiresThemImmediately() {
        var task = publish(false);
        var submittedDraft = drafts.create(task.publicId(), "EXPIRY-001");
        var openDraft = drafts.create(task.publicId(), "EXPIRY-002");
        LocalDateTime extendedEnd = task.endsAt().plusDays(2);
        var updated = tasks.update(task.id(), commandFrom(task, extendedEnd), task.version(), admin);

        assertThat(jdbc.sql("SELECT expires_at FROM recruitment_draft WHERE id=:id")
                .param("id", submittedDraft.draftId()).query(LocalDateTime.class).single())
                .isEqualTo(extendedEnd);
        assertThat(jdbc.sql("SELECT expires_at FROM recruitment_draft WHERE id=:id")
                .param("id", openDraft.draftId()).query(LocalDateTime.class).single())
                .isEqualTo(extendedEnd);

        applications.submit(task.publicId(), submittedDraft.draftId(), submittedDraft.draftToken(),
                "EXPIRY-001", validAnswers());
        tasks.close(task.id(), updated.version(), admin);

        assertThat(jdbc.sql("SELECT status FROM recruitment_draft WHERE id=:id")
                .param("id", submittedDraft.draftId()).query(String.class).single())
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.sql("SELECT status FROM recruitment_draft WHERE id=:id")
                .param("id", openDraft.draftId()).query(String.class).single())
                .isEqualTo("EXPIRED");
        // expires_at 是 DATETIME(6)：系统时钟的亚微秒部分入库时会四舍五入，
        // 存进去的值可能比写它的那一刻晚最多 500ns。断言的是“关闭后立刻过期、
        // 不再是未来的截止时间”，所以放开一微秒的入库取整误差。
        assertThat(jdbc.sql("SELECT expires_at FROM recruitment_draft WHERE id=:id")
                .param("id", openDraft.draftId()).query(LocalDateTime.class).single())
                .isBeforeOrEqualTo(LocalDateTime.now(clock).plusNanos(1_000));
    }

    @Test
    void anonymousLimiterReturns429AndSeparatesMutationActions() {
        AnonymousRecruitmentRateLimiter limiter = new AnonymousRecruitmentRateLimiter(clock);
        String publicId = "A".repeat(26);
        for (int i = 0; i < AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE.limit(); i++) {
            limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                    publicId, "203.0.113.7");
        }
        assertThatThrownBy(() -> limiter.requireAllowed(
                AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                publicId, "203.0.113.7"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(business.getCode().status().value()).isEqualTo(429);
                });

        limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.UPLOAD_CREATE,
                publicId, "203.0.113.7");
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);
    }

    @Test
    void anonymousLimiterIgnoresInvalidOrSharedProxyKeysAndFailsOpenAtCapacity() {
        AnonymousRecruitmentRateLimiter limiter = new AnonymousRecruitmentRateLimiter(clock);
        String publicId = "B".repeat(26);
        for (int i = 0; i < 100; i++) {
            limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                    publicId, "10.0.0.5");
            limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                    "invalid-task-" + i, "203.0.113.8");
        }
        assertThat(limiter.trackedKeyCount()).isZero();

        for (int i = 0; i < AnonymousRecruitmentRateLimiter.MAX_TRACKED_KEYS; i++) {
            limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                    publicId, "11." + (i / 256) + "." + (i % 256) + ".1");
        }
        assertThat(limiter.trackedKeyCount())
                .isEqualTo(AnonymousRecruitmentRateLimiter.MAX_TRACKED_KEYS);
        limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                publicId, "12.0.0.1");
        assertThat(limiter.trackedKeyCount())
                .isEqualTo(AnonymousRecruitmentRateLimiter.MAX_TRACKED_KEYS);
    }

    @Test
    void publicControllerValidatesActiveTaskBeforeAllocatingLimiterState() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.91");
        int before = sharedLimiter.trackedKeyCount();

        assertThatThrownBy(() -> publicController.createDraft("not-a-public-id",
                new RecruitmentPublicController.CreateDraftRequest("VALID-001"), request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(sharedLimiter.trackedKeyCount()).isEqualTo(before);

        var task = publish(false);
        request.setRemoteAddr("10.0.0.8");
        publicController.createDraft(task.publicId(),
                new RecruitmentPublicController.CreateDraftRequest("VALID-002"), request);
        assertThat(sharedLimiter.trackedKeyCount()).isEqualTo(before);
    }

    @Test
    void databaseUniqueKeyRemainsTheConcurrencyBoundaryForEquivalentStudents() {
        var task = publish(false);
        var first = drafts.create(task.publicId(), "ABC_001");
        var concurrent = drafts.create(task.publicId(), "abc_001");
        applications.submit(task.publicId(), first.draftId(), first.draftToken(),
                "ABC_001", validAnswers());

        String schemaJson = taskMapper.selectById(task.id()).getFormSchemaJson();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO recruitment_application
                    (id, task_id, draft_id, student_id, normalized_student_id,
                     answers_json, form_schema_json, submitted_at)
                VALUES ('01KTESTCONCURRENTBOUNDARY1', :taskId, :draftId, 'abc_001',
                        'ABC_001', '{}', :schema, CURRENT_TIMESTAMP)
                """).param("taskId", task.id()).param("draftId", concurrent.draftId())
                .param("schema", schemaJson).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiredAnswersAndChoiceValuesAreValidatedBeforeSubmission() {
        var task = publish(false);
        var ticket = drafts.create(task.publicId(), "VALID-001");
        assertThatThrownBy(() -> applications.submit(task.publicId(), ticket.draftId(),
                ticket.draftToken(), "VALID-001", Map.of("motivation", "", "campus", "非法选项")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("报名理由");

        var unknown = drafts.create(task.publicId(), "VALID-002");
        assertThatThrownBy(() -> applications.submit(task.publicId(), unknown.draftId(),
                unknown.draftToken(), "VALID-002",
                Map.of("motivation", "愿意参加", "campus", "东校区", "hidden", "payload")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未知字段");
    }

    @Test
    void requiredUploadAndInProgressUploadBothBlockImmutableSubmission() {
        var requiredTask = publish(true);
        var noFile = drafts.create(requiredTask.publicId(), "UPLOAD-001");
        assertThatThrownBy(() -> applications.submit(requiredTask.publicId(), noFile.draftId(),
                noFile.draftToken(), "UPLOAD-001", validAnswers()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少上传一张图片");

        var optionalTask = publish(false);
        var processing = drafts.create(optionalTask.publicId(), "UPLOAD-002");
        jdbc.sql("""
                INSERT INTO recruitment_upload_batch
                    (id, draft_id, mode, status, total_count, success_count, failure_count)
                VALUES ('01KTESTPROCESSINGBATCH01', :draftId, 'FILES', 'PROCESSING', 1, 0, 0)
                """).param("draftId", processing.draftId()).update();
        assertThatThrownBy(() -> applications.submit(optionalTask.publicId(), processing.draftId(),
                processing.draftToken(), "UPLOAD-002", validAnswers()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("处理中");
    }

    @Test
    void generatedDetailsMarkdownEscapesApplicantTextLabelsAndFileNames() {
        var field = new RecruitmentFormSchema.Field("bio", RecruitmentFieldType.LONG_TEXT,
                "标题 # *危险*", null, null, true, List.of());
        String markdown = applications.detailsMarkdown("任务 [链接](https://bad.example) bad@example.com www.bad.example",
                "ID#1", LocalDateTime.of(2026, 8, 22, 12, 0),
                new RecruitmentFormSchema(List.of(field)),
                Map.of("bio", "<script>alert(1)</script>\n# 接管标题 *加粗* ~~删除线~~"),
                List.of(new RecruitmentAttachmentReader.Attachment(
                        1, "[点击我](https://bad.example).jpg", "image/jpeg", 12,
                        "recruitments/final/x.jpg", "a".repeat(64))));

        assertThat(markdown).contains("任务 \\[链接\\]\\(https://bad\\.example\\)")
                .contains("标题 \\# \\*危险\\*")
                .contains("\\<script\\>alert\\(1\\)\\</script\\>")
                .contains("\\# 接管标题 \\*加粗\\*")
                .contains("\\~\\~删除线\\~\\~")
                .contains("\\[点击我\\]\\(https://bad\\.example\\)\\.jpg")
                .contains("bad@example\\.com", "www\\.bad\\.example")
                .doesNotContain("[点击我](https://bad.example)");
    }

    @Test
    void schemaValidatorCapsFieldsRequiresStableIdsAndRejectsDuplicateOptions() {
        List<RecruitmentFormSchema.Field> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(new RecruitmentFormSchema.Field("field_" + i,
                    RecruitmentFieldType.SHORT_TEXT, "字段" + i, null, null, false, List.of()));
        }
        assertThatThrownBy(() -> schemas.validate(new RecruitmentFormSchema(tooMany)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最多 50");

        var unstable = new RecruitmentFormSchema.Field("Bad ID", RecruitmentFieldType.SHORT_TEXT,
                "字段", null, null, false, List.of());
        assertThatThrownBy(() -> schemas.validate(new RecruitmentFormSchema(List.of(unstable))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("字段 ID");

        var duplicated = new RecruitmentFormSchema.Field("choice", RecruitmentFieldType.SINGLE_CHOICE,
                "选择", null, null, true, List.of("A", "A"));
        assertThatThrownBy(() -> schemas.validate(new RecruitmentFormSchema(List.of(duplicated))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("选项不能重复");
    }

    @Test
    void applicationSearchNormalizesStudentIdFragmentAndTreatsLikeWildcardsLiterally() {
        var task = publish(false);
        submitAs(task, "AB_001");
        submitAs(task, "ABX001");
        submitAs(task, "２０２３ ００１a");

        assertThat(applications.list(task.id(), 1, 20, null, admin).items())
                .extracting(RecruitmentApplicationService.ApplicationSummary::studentId)
                .containsExactlyInAnyOrder("AB_001", "ABX001", "2023 001a");
        assertThat(applications.list(task.id(), 1, 20, "   ", admin).total()).isEqualTo(3);

        // '_' is a legal identifier character and a LIKE wildcard; it must match literally.
        assertThat(applications.list(task.id(), 1, 20, "B_0", admin).items())
                .extracting(RecruitmentApplicationService.ApplicationSummary::studentId)
                .containsExactly("AB_001");
        assertThat(applications.list(task.id(), 1, 20, "%", admin).items()).isEmpty();

        // The search box is normalized exactly like the stored identifier.
        var byFullWidth = applications.list(task.id(), 1, 20, " ００1ａ ", admin);
        assertThat(byFullWidth.total()).isOne();
        assertThat(byFullWidth.items())
                .extracting(RecruitmentApplicationService.ApplicationSummary::studentId)
                .containsExactly("2023 001a");

        assertThat(applications.list(task.id(), 1, 20, "NO-SUCH-STUDENT", admin).total()).isZero();
    }

    @Test
    void applicationExportMatchesTheListFilterAndCountsFinalizedAttachments() throws Exception {
        var task = publish(false);
        submitAs(task, "AB_001");
        String withFiles = submitAs(task, "ABX001");
        attachTwoFinalizedImages(withFiles);

        var full = applications.export(task.id(), null, admin);
        assertThat(full.fileName())
                .isEqualTo("2026 秋季招募-报名-" + LocalDate.now(clock) + ".xlsx");
        List<List<String>> rows = readSheet(full.content());
        assertThat(rows.get(0)).containsExactly("学号", "提交时间", "附件数量",
                "报名理由", "所在校区", "可入部日期");
        assertThat(rows.subList(1, rows.size())).extracting(row -> row.get(0))
                .containsExactlyInAnyOrder("AB_001", "ABX001");
        List<String> withAttachments = rowFor(rows, "ABX001");
        assertThat(withAttachments.get(1)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(withAttachments).element(2).isEqualTo("2");
        assertThat(withAttachments.subList(3, withAttachments.size()))
                .containsExactly("热爱摄影，希望参与校园记录。", "东校区", "2026-09-01");
        assertThat(rowFor(rows, "AB_001")).element(2).isEqualTo("0");

        // 导出口径必须和列表一致：搜索框里输了什么，导出的就是那一批。
        List<List<String>> filtered = readSheet(applications.export(task.id(), "B_0", admin).content());
        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(1).get(0)).isEqualTo("AB_001");
        assertThat(readSheet(applications.export(task.id(), "NO-SUCH-STUDENT", admin).content()))
                .hasSize(1);
    }

    @Test
    void applicationExportRequiresRecruitmentViewPermission() {
        var task = publish(false);
        var outsider = new AuthenticatedUser(9_703L, "outsider", "无权限用户",
                UserRole.CAMPUS_MANAGER, 1L, false, -1L, "NONE", "NONE",
                DataScope.GLOBAL, Set.of(), Set.of(1L));

        assertThatThrownBy(() -> applications.export(task.id(), null, outsider))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static List<String> rowFor(List<List<String>> rows, String studentId) {
        return rows.stream().filter(row -> row.get(0).equals(studentId)).findFirst().orElseThrow();
    }

    /** 直接写库造两个已完成的附件，导出只数这种“真的传上来了”的对象。 */
    private void attachTwoFinalizedImages(String draftId) {
        String batchId = draftId.substring(0, 25) + "B";
        jdbc.sql("""
                INSERT INTO recruitment_upload_batch
                    (id, draft_id, mode, status, total_count, success_count, failure_count,
                     created_at, updated_at)
                VALUES (:id, :draftId, 'FILES', 'SUCCEEDED', 3, 2, 1,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).param("id", batchId).param("draftId", draftId).update();
        for (int index = 0; index < 2; index++) {
            insertItem(batchId, "作品" + index + ".jpg", "recruitments/final/" + batchId + "-" + index,
                    "SUCCEEDED");
        }
        // 失败的那张既没有对象也不该被数进去。
        insertItem(batchId, "坏掉的.jpg", null, "FAILED");
    }

    private void insertItem(String batchId, String fileName, String objectKey, String status) {
        jdbc.sql("""
                INSERT INTO recruitment_upload_item
                    (batch_id, original_file_name, object_key, content_type, size, status,
                     created_at, updated_at)
                VALUES (:batchId, :fileName, :objectKey, 'image/jpeg', 1024, :status,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).param("batchId", batchId).param("fileName", fileName)
                .param("objectKey", objectKey).param("status", status).update();
    }

    /** 把导出的 XLSX 读回来，每行按表头宽度补齐，空单元格读成空串。 */
    private static List<List<String>> readSheet(byte[] content) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            int width = sheet.getRow(0).getLastCellNum();
            List<List<String>> rows = new ArrayList<>();
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(index);
                List<String> values = new ArrayList<>(width);
                for (int column = 0; column < width; column++) {
                    Cell cell = row == null ? null : row.getCell(column);
                    values.add(cell == null ? "" : switch (cell.getCellType()) {
                        case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                        case BLANK -> "";
                        default -> cell.getStringCellValue();
                    });
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private RecruitmentTaskService.TaskView publish(boolean uploadRequired) {
        var created = tasks.create(command(uploadRequired), admin);
        return tasks.publish(created.id(), created.version(), admin);
    }

    private RecruitmentTaskService.TaskCommand command(boolean uploadRequired) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new RecruitmentTaskService.TaskCommand(
                "2026 秋季招募", "欢迎加入摄影部",
                schema(), "学号", "请填写教务系统学号", "作品图片",
                "支持 JPG、PNG 或 ZIP", uploadRequired,
                now.minusMinutes(1), now.plusDays(3));
    }

    private RecruitmentTaskService.TaskCommand commandFrom(
            RecruitmentTaskService.TaskView task, LocalDateTime endsAt) {
        return new RecruitmentTaskService.TaskCommand(task.title(), task.introMarkdown(),
                task.formSchema(), task.studentIdLabel(), task.studentIdHelp(), task.uploadLabel(),
                task.uploadHelp(), task.uploadRequired(), task.startsAt(), endsAt);
    }

    private RecruitmentFormSchema schema() {
        return new RecruitmentFormSchema(List.of(
                new RecruitmentFormSchema.Field("motivation", RecruitmentFieldType.LONG_TEXT,
                        "报名理由", "介绍自己", "请输入报名理由", true, List.of()),
                new RecruitmentFormSchema.Field("campus", RecruitmentFieldType.SINGLE_CHOICE,
                        "所在校区", null, "请选择校区", true, List.of("东校区", "西校区")),
                new RecruitmentFormSchema.Field("available_date", RecruitmentFieldType.DATE,
                        "可入部日期", null, "YYYY-MM-DD", false, List.of())));
    }

    private Map<String, Object> validAnswers() {
        return Map.of("motivation", "热爱摄影，希望参与校园记录。", "campus", "东校区",
                "available_date", "2026-09-01");
    }

    /** 提交一份报名，返回它绑定的草稿 id——附件挂在草稿上。 */
    private String submitAs(RecruitmentTaskService.TaskView task, String studentId) {
        var ticket = drafts.create(task.publicId(), studentId);
        applications.submit(task.publicId(), ticket.draftId(), ticket.draftToken(),
                studentId, validAnswers());
        return ticket.draftId();
    }
}

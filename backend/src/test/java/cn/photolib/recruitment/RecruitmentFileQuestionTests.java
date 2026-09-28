package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.form.FormFileCleanupJob;
import cn.photolib.form.FormFileService;
import cn.photolib.recruitment.model.RecruitmentFieldType;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 招募表单里自由选用的「上传文件」题目。 */
@SpringBootTest
@Transactional
class RecruitmentFileQuestionTests {
    private static final long ADMIN_ID = 9_721L;

    @Autowired private RecruitmentTaskService tasks;
    @Autowired private RecruitmentDraftService drafts;
    @Autowired private RecruitmentApplicationService applications;
    @Autowired private FormFileCleanupJob cleanup;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;

    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'recruitment-file-admin', 'hash', '招募文件测试', 'ADMIN', TRUE, FALSE)
                """).param("id", ADMIN_ID).update();
        admin = new AuthenticatedUser(ADMIN_ID, "recruitment-file-admin", "招募文件测试",
                UserRole.ADMIN, null, false);
    }

    @Test
    void uploadedFilesAreVerifiedAttachedAndShownInDetailAndExport() throws Exception {
        var task = publish(true);
        var draft = drafts.create(task.publicId(), "FILE-001");
        var ticket = applications.createFileTicket(task.publicId(), draft.draftId(), draft.draftToken(),
                new FormFileService.TicketRequest("resume", "简历 v2.PDF", "Application/PDF; charset=x", 4L));
        assertThat(ticket.contentType()).isEqualTo("application/pdf");
        assertThat(ticket.uploadUrl()).isNotBlank();
        assertThatThrownBy(() -> applications.createFileTicket(task.publicId(), draft.draftId(), "wrong-token",
                new FormFileService.TicketRequest("resume", "a.pdf", "application/pdf", 4L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> applications.createFileTicket(task.publicId(), draft.draftId(),
                draft.draftToken(), new FormFileService.TicketRequest("resume", "huge.mov", "video/quicktime",
                        FormFileService.MAX_FILE_BYTES + 1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("超过了");

        // 必填的上传题没交文件，不能报名。
        assertThatThrownBy(() -> applications.submit(task.publicId(), draft.draftId(), draft.draftToken(),
                "FILE-001", Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必填");

        put(ticket.fileId(), "%PDF");
        var receipt = applications.submit(task.publicId(), draft.draftId(), draft.draftToken(), "FILE-001",
                Map.of("resume", List.of(ticket.fileId())));

        var detail = applications.get(receipt.applicationId(), admin);
        assertThat(detail.files()).singleElement().satisfies(file -> {
            assertThat(file.fieldId()).isEqualTo("resume");
            assertThat(file.fileName()).isEqualTo("简历 v2.PDF");
            assertThat(file.downloadUrl()).isNotBlank();
        });
        assertThat(detail.detailsMarkdown()).contains("简历 v2\\.PDF");

        var export = applications.export(task.id(), null, admin);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("个人简历");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("简历 v2.PDF");
        }

        // 同一个文件不能再被另一份报名引用。
        var other = drafts.create(task.publicId(), "FILE-002");
        assertThatThrownBy(() -> applications.submit(task.publicId(), other.draftId(), other.draftToken(),
                "FILE-002", Map.of("resume", List.of(ticket.fileId()))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("找不到");
    }

    @Test
    void cleanupRemovesOnlyExpiredUnsubmittedFiles() {
        var task = publish(false);
        var draft = drafts.create(task.publicId(), "FILE-003");
        var abandoned = applications.createFileTicket(task.publicId(), draft.draftId(), draft.draftToken(),
                new FormFileService.TicketRequest("resume", "old.pdf", "application/pdf", 3L));
        var fresh = applications.createFileTicket(task.publicId(), draft.draftId(), draft.draftToken(),
                new FormFileService.TicketRequest("resume", "new.pdf", "application/pdf", 3L));
        put(abandoned.fileId(), "old");
        String abandonedKey = objectKey(abandoned.fileId());
        jdbc.sql("UPDATE form_file_upload SET upload_url_expires_at=:at WHERE id=:id")
                .param("at", LocalDateTime.now(clock).minusHours(2)).param("id", abandoned.fileId()).update();

        assertThat(cleanup.cleanup()).isGreaterThanOrEqualTo(1);
        assertThat(count(abandoned.fileId())).isZero();
        assertThat(storage.find(abandonedKey)).isEmpty();
        assertThat(count(fresh.fileId())).isOne();
    }

    @Test
    void schemaRejectsOptionsOnFileQuestionsAndMalformedFileAnswers() {
        var badField = new RecruitmentFormSchema.Field("resume", RecruitmentFieldType.FILE_UPLOAD, "简历",
                null, null, false, List.of("a", "b"));
        assertThatThrownBy(() -> tasks.create(command(new RecruitmentFormSchema(List.of(badField))), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非选择字段不能设置选项");

        var task = publish(false);
        var draft = drafts.create(task.publicId(), "FILE-004");
        assertThatThrownBy(() -> applications.submit(task.publicId(), draft.draftId(), draft.draftToken(),
                "FILE-004", Map.of("resume", "not-a-list")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("必须是文件列表");
        assertThatThrownBy(() -> applications.submit(task.publicId(), draft.draftId(), draft.draftToken(),
                "FILE-004", Map.of("resume", List.of("../../etc/passwd"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无效的文件");
    }

    private RecruitmentTaskService.TaskView publish(boolean required) {
        var created = tasks.create(command(new RecruitmentFormSchema(List.of(
                new RecruitmentFormSchema.Field("resume", RecruitmentFieldType.FILE_UPLOAD, "个人简历",
                        "PDF 或 Word 都行", null, required, List.of())))), admin);
        return tasks.publish(created.id(), created.version(), admin);
    }

    private RecruitmentTaskService.TaskCommand command(RecruitmentFormSchema schema) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new RecruitmentTaskService.TaskCommand("文件题招募", null, schema, "学号", null,
                "作品图片", null, false, now.minusMinutes(1), now.plusDays(3));
    }

    private void put(String fileId, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.put(objectKey(fileId), new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
    }

    private String objectKey(String fileId) {
        return jdbc.sql("SELECT object_key FROM form_file_upload WHERE id=:id")
                .param("id", fileId).query(String.class).single();
    }

    private long count(String fileId) {
        return jdbc.sql("SELECT COUNT(*) FROM form_file_upload WHERE id=:id")
                .param("id", fileId).query(Long.class).single();
    }
}

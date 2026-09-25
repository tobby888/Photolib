package cn.photolib.teaching;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.OfficeUpload;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.teaching.mapper.TeachingMaterialMapper;
import cn.photolib.teaching.model.TeachingMaterialEntity;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 教学资料的业务规则（Service 切面）。
 *
 * <p>用真实的本地存储 + 真实的库跑：文件真的进对象存储、计数真的落库、通知真的入库，
 * 这些正是"只在 Service 层测才看得出对不对"的地方。</p>
 */
@SpringBootTest
@Transactional
class TeachingServiceTests {
    private static final long MINISTER_ID = 9_801L;
    private static final long MEMBER_ID = 9_802L;
    private static final long OUTSIDER_ID = 9_803L;
    private static final byte[] PDF_BYTES = "%PDF-1.4 假装这是一份课件".getBytes(StandardCharsets.UTF_8);

    @Autowired private TeachingService service;
    @Autowired private TeachingMaterialMapper mapper;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;

    private AuthenticatedUser minister;

    @BeforeEach
    void setUp() {
        insertUser(MINISTER_ID, "teaching-minister", "教学部长", "MINISTER", null);
        insertUser(MEMBER_ID, "teaching-member", "图库成员", "CAMPUS_MANAGER", null);
        // 未分配权限组（NO_ACCESS）的账号不是图库成员：不能当作者，也收不到通知。
        insertUser(OUTSIDER_ID, "teaching-outsider", "外部账号", "CAMPUS_MANAGER", "NO_ACCESS");
        minister = new AuthenticatedUser(MINISTER_ID, "teaching-minister", "教学部长",
                UserRole.MINISTER, null, false);
    }

    @Test
    void createdMaterialsAreListedWithTheirMetadataAndStoredFile() throws IOException {
        TeachingService.Material created = service.create("摄影基础课件", "第一课的讲义",
                "摄影基础", MEMBER_ID, pdfUpload(), minister);

        assertThat(created.publicId()).hasSize(26);
        assertThat(created.format()).isEqualTo("PDF");
        assertThat(created.downloadCount()).isZero();
        assertThat(created.authorName()).isEqualTo("图库成员");
        assertThat(created.uploaderName()).isEqualTo("教学部长");

        TeachingMaterialEntity entity = mapper.findByPublicId(created.publicId());
        assertThat(entity.getObjectKey())
                .isEqualTo("teaching/" + created.publicId() + "/document.pdf");
        assertThat(read(entity.getObjectKey())).startsWith("%PDF-");

        assertThat(service.list(null, null)).extracting(TeachingService.Material::title)
                .containsExactly("摄影基础课件");
    }

    @Test
    void uploadsThatAreNotPdfAreRejectedAndLeaveNothingBehind() {
        MockMultipartFile disguised = new MockMultipartFile("file", "假装.pdf", "application/pdf",
                "GIF89a not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.create("伪装的资料", null, "摄影基础", null, disguised, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
        assertThat(service.list(null, null)).isEmpty();
    }

    @Test
    void oversizedPdfUploadsAreRejected() {
        // 只改 size，不真的分配 100 MiB：校验必须在读字节之前就按声明大小拒绝。
        MockMultipartFile oversized = new MockMultipartFile("file", "超大.pdf", "application/pdf",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public long getSize() {
                return OfficeUpload.MAX_BYTES + 1;
            }
        };

        assertThatThrownBy(() -> service.create("超大资料", null, "摄影基础", null, oversized, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FILE_TOO_LARGE);
        assertThat(service.list(null, null)).isEmpty();
    }

    @Test
    void wordAndPptUploadsAreDetectedAndStoredWithTheirFormat() throws IOException {
        TeachingService.Material handout = service.create("讲义", null, "摄影基础",
                null, docxUpload(), minister);
        assertThat(handout.format()).isEqualTo("WORD");
        assertThat(mapper.findByPublicId(handout.publicId()).getObjectKey())
                .isEqualTo("teaching/" + handout.publicId() + "/document.docx");

        TeachingService.Material slides = service.create("课件", null, "摄影基础",
                null, pptxUpload(), minister);
        assertThat(slides.format()).isEqualTo("PPT");
        assertThat(mapper.findByPublicId(slides.publicId()).getObjectKey())
                .isEqualTo("teaching/" + slides.publicId() + "/document.pptx");
    }

    @Test
    void aZipThatIsNeitherWordNorPptIsRejected() {
        MockMultipartFile zip = zipUpload("notes.zip", "application/zip", "random.txt");

        assertThatThrownBy(() -> service.create("假 zip", null, "摄影基础", null, zip, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    void replacingAFileWithADifferentFormatIsRejected() throws IOException {
        TeachingService.Material created = service.create("格式固定", null, "摄影基础",
                null, pdfUpload(), minister);

        assertThatThrownBy(() -> service.replaceFile(created.id(), docxUpload(),
                created.version(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
        // 换不同格式被拒后，原 PDF 对象键保持不动。
        assertThat(mapper.findByPublicId(created.publicId()).getObjectKey())
                .isEqualTo("teaching/" + created.publicId() + "/document.pdf");
    }

    @Test
    void replacingTheFileKeepsTheObjectKeyAndRejectsAStaleVersion() throws IOException {
        TeachingService.Material created = service.create("会更新的课件", null, "后期",
                null, pdfUpload(), minister);
        String objectKey = mapper.findByPublicId(created.publicId()).getObjectKey();

        service.replaceFile(created.id(), pdfUpload("%PDF-1.7 第二版"), created.version(), minister);

        // 对象键跟着 publicId 走：读者手上的链接继续有效。
        assertThat(mapper.findByPublicId(created.publicId()).getObjectKey()).isEqualTo(objectKey);
        assertThat(read(objectKey)).contains("第二版");
        assertThatThrownBy(() -> service.replaceFile(created.id(), pdfUpload(), 1, minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void downloadCountIncrementsOnlyWhenADownloadIsRecorded() throws IOException {
        TeachingService.Material created = service.create("计数课件", null, "器材",
                null, pdfUpload(), minister);

        // 预览读的是同一个对象，不经过 download，所以计数保持 0。
        assertThat(service.get(created.publicId()).downloadCount()).isZero();
        TeachingService.Download download = service.download(created.publicId());

        assertThat(download.fileName()).isEqualTo("计数课件.pdf");
        assertThat(download.downloadUrl()).isNotBlank();
        assertThat(service.get(created.publicId()).downloadCount()).isEqualTo(1);
    }

    @Test
    void softDeletedMaterialsDisappearFromTheList() throws IOException {
        TeachingService.Material created = service.create("待删除课件", null, "摄影基础",
                null, pdfUpload(), minister);

        service.delete(created.id(), created.version(), minister);

        assertThat(service.list(null, null)).isEmpty();
        assertThatThrownBy(() -> service.get(created.publicId()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void duplicateTitlesAreRejected() throws IOException {
        service.create("同名课件", null, "摄影基础", null, pdfUpload(), minister);

        assertThatThrownBy(() -> service.create("  同名课件 ", null, "后期",
                null, pdfUpload(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void creatingAMaterialNotifiesEveryGalleryMember() throws IOException {
        service.create("新课件", null, "摄影基础", null, pdfUpload(), minister);

        assertThat(notificationsFor(MEMBER_ID)).isEqualTo(1);
        // 上传人自己也是图库成员，同样会收到通知。
        assertThat(notificationsFor(MINISTER_ID)).isEqualTo(1);
        // NO_ACCESS 账号不是图库成员，收不到。
        assertThat(notificationsFor(OUTSIDER_ID)).isZero();
        // 只发站内信：绑了企业微信的成员也不该排外发。
        jdbc.sql("UPDATE app_user SET wecom_userid = 'teaching-member' WHERE id = :id")
                .param("id", MEMBER_ID).update();
        service.create("又一份课件", null, "摄影基础", null, pdfUpload(), minister);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM notification_log WHERE event_type = 'TEACHING_PUBLISHED'
                """).query(Long.class).single()).isZero();
    }

    @Test
    void editingKeepsAnAuthorWhoLeftTheGalleryAndTheOriginalUploader() throws IOException {
        TeachingService.Material created = service.create("作者离开", null, "摄影基础",
                MEMBER_ID, pdfUpload(), minister);
        jdbc.sql("UPDATE app_user SET enabled = FALSE WHERE id = :id").param("id", MEMBER_ID).update();
        AuthenticatedUser editor = new AuthenticatedUser(OUTSIDER_ID, "teaching-outsider", "外部账号",
                UserRole.CAMPUS_MANAGER, null, false);

        // 只改标题、作者原样提交：原作者已不是图库成员，也不能挡住这次编辑。
        TeachingService.Material edited = service.updateMetadata(created.id(), "作者离开（修订）",
                null, "摄影基础", MEMBER_ID, created.version(), editor);

        assertThat(edited.authorId()).isEqualTo(MEMBER_ID);
        // 上传人是录入的人，不随最后一次编辑变。
        assertThat(edited.uploaderName()).isEqualTo("教学部长");
    }

    @Test
    void theAuthorMustBeAGalleryMember() {
        assertThatThrownBy(() -> service.create("作者无效", null, "摄影基础",
                OUTSIDER_ID, pdfUpload(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void renamingACategoryMovesEveryMaterialInIt() throws IOException {
        service.create("课件甲", null, "摄影基础", null, pdfUpload(), minister);
        service.create("课件乙", null, "摄影基础", null, pdfUpload(), minister);
        service.create("课件丙", null, "后期", null, pdfUpload(), minister);

        int updated = service.renameCategory("摄影基础", "摄影入门");

        assertThat(updated).isEqualTo(2);
        assertThat(service.categories()).containsExactly("后期", "摄影入门");
        assertThat(service.list("摄影入门", null)).hasSize(2);
    }

    @Test
    void renamingACategoryPushesVersionsSoStaleEditsAreRejected() throws IOException {
        TeachingService.Material created = service.create("分类课件", null, "旧分类",
                null, pdfUpload(), minister);

        service.renameCategory("旧分类", "新分类");

        // 重命名推进了 version：拿着改名前的版本号回来保存，必须 409，而不是把分类悄悄改回去。
        assertThatThrownBy(() -> service.updateMetadata(created.id(), "分类课件", null, "旧分类",
                null, created.version(), minister))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void theListFiltersByCategoryAndByQuery() throws IOException {
        service.create("构图讲义", "讲三分法", "摄影基础", null, pdfUpload(), minister);
        service.create("调色讲义", "讲曲线", "后期", null, pdfUpload(), minister);

        assertThat(service.list("后期", null)).extracting(TeachingService.Material::title)
                .containsExactly("调色讲义");
        assertThat(service.list(null, "三分法")).extracting(TeachingService.Material::title)
                .containsExactly("构图讲义");
        assertThat(service.list(null, null)).hasSize(2);
    }

    private void insertUser(long id, String username, String displayName, String role,
                            String permissionGroupCode) {
        Long groupId = permissionGroupCode == null ? null : jdbc.sql(
                        "SELECT id FROM permission_group WHERE code = :code")
                .param("code", permissionGroupCode).query(Long.class).optional().orElse(null);
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled,
                     must_change_password, permission_group_id)
                VALUES (:id, :username, 'hash', :displayName, :role, TRUE, FALSE, :groupId)
                """).param("id", id).param("username", username).param("displayName", displayName)
                .param("role", role).param("groupId", groupId).update();
    }

    private long notificationsFor(long userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE user_id = :id AND event_type = 'TEACHING_PUBLISHED'
                """).param("id", userId).query(Long.class).single();
    }

    private String read(String objectKey) {
        try (InputStream input = storage.open(objectKey)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private MockMultipartFile pdfUpload() {
        return pdfUpload(new String(PDF_BYTES, StandardCharsets.UTF_8));
    }

    private MockMultipartFile pdfUpload(String content) {
        return new MockMultipartFile("file", "课件.pdf", "application/pdf",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile docxUpload() {
        return zipUpload("讲义.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "[Content_Types].xml", "word/document.xml");
    }

    private MockMultipartFile pptxUpload() {
        return zipUpload("课件.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "[Content_Types].xml", "ppt/presentation.xml");
    }

    private MockMultipartFile zipUpload(String filename, String contentType, String... entries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                for (String entry : entries) {
                    zip.putNextEntry(new ZipEntry(entry));
                    zip.write(new byte[]{1, 2, 3});
                    zip.closeEntry();
                }
            }
            return new MockMultipartFile("file", filename, contentType, buffer.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

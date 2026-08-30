package cn.photolib.recruitment.upload;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.util.PublicId;
import cn.photolib.recruitment.RecruitmentAttachmentReader;
import cn.photolib.recruitment.RecruitmentDraftService;
import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RecruitmentUploadServiceTests {
    private static final long USER_ID = 89101;
    private static final long TASK_ID = 89102;

    @Autowired
    private RecruitmentUploadService service;
    @Autowired
    private RecruitmentDraftService draftService;
    @Autowired
    private RecruitmentAttachmentReader attachmentReader;
    @Autowired
    private RecruitmentExpiredDraftCleanupJob cleanupJob;
    @Autowired
    private RecruitmentTemporaryObjectCleanupJob temporaryCleanupJob;
    @Autowired
    private RecruitmentUploadProcessor processor;
    @Autowired
    @Qualifier("recruitmentUploadExecutor")
    private ThreadPoolTaskExecutor recruitmentUploadExecutor;
    @Autowired
    private RecruitmentUploadProperties uploadProperties;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;
    // The production code reads time from this fixed-zone clock (Asia/Shanghai), which is
    // independent of the host time zone. Fixtures must use the same clock instead of the
    // database's CURRENT_TIMESTAMP, or the window they write is off by the host's offset.
    @Autowired
    private Clock recruitmentClock;

    private String publicId;

    @BeforeEach
    void setUp() {
        cleanFixture();
        publicId = PublicId.next();
        LocalDateTime now = now();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, permission_group_id,
                     enabled, must_change_password, version, deleted)
                VALUES (:id, 'recruitment-upload-test', 'hash', '招募上传测试', 'ADMIN',
                    (SELECT id FROM permission_group WHERE code='ADMIN'),
                    TRUE, FALSE, 1, FALSE)
                """).param("id", USER_ID).update();
        jdbc.sql("""
                INSERT INTO recruitment_task
                    (id, public_id, title, intro_markdown, form_schema_json,
                     student_id_label, upload_label, upload_required, starts_at, ends_at,
                     status, created_by, published_by, published_at, version, deleted)
                VALUES (:id, :publicId, '招募上传测试', '', '{"fields":[]}',
                    '学号', '附件', FALSE, :startsAt, :endsAt, 'PUBLISHED', :userId, :userId,
                    :publishedAt, 1, FALSE)
                """).param("id", TASK_ID).param("publicId", publicId)
                .param("startsAt", now.minusHours(1)).param("endsAt", now.plusHours(2))
                .param("publishedAt", now)
                .param("userId", USER_ID).update();
    }

    @AfterEach
    void tearDown() {
        cleanFixture();
    }

    @Test
    void anonymousQuotaIsTheRecruitmentOneNotTheGallerys() {
        var draft = draftService.create(publicId, "20260101");
        String sha = "a".repeat(64);
        long overImage = uploadProperties.maxImageBytes() + 1;

        // The gallery accepts 100 MiB per image; this path must not inherit that.
        assertThat(overImage).isLessThanOrEqualTo(ImageUploadPolicy.MAX_IMAGE_BYTES);
        assertThatThrownBy(() -> service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.FILES, null, null,
                        List.of(new RecruitmentUploadService.FileSpec(
                                "big.jpg", "image/jpeg", overImage, sha)))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("单张图片不得超过");

        List<RecruitmentUploadService.FileSpec> tooMany = new ArrayList<>();
        for (int index = 0; index <= uploadProperties.maxImageCount(); index++) {
            tooMany.add(new RecruitmentUploadService.FileSpec(
                    index + ".jpg", "image/jpeg", 1024, sha));
        }
        assertThat(tooMany).hasSizeLessThanOrEqualTo(ImageUploadPolicy.MAX_IMAGE_COUNT);
        assertThatThrownBy(() -> service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(
                        RecruitmentUploadMode.FILES, null, null, tooMany)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("一次可上传");

        long overArchive = uploadProperties.maxArchiveBytes() + 1;
        assertThat(overArchive).isLessThanOrEqualTo(ImageUploadPolicy.MAX_ARCHIVE_BYTES);
        assertThatThrownBy(() -> service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(
                        RecruitmentUploadMode.ZIP, "works.zip", overArchive, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("压缩包不得超过");
    }

    @Test
    void quotaAtTheBoundaryIsStillAccepted() {
        var draft = draftService.create(publicId, "20260102");
        var ticket = service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.FILES, null, null,
                        List.of(new RecruitmentUploadService.FileSpec("edge.jpg", "image/jpeg",
                                uploadProperties.maxImageBytes(), "b".repeat(64)))));

        assertThat(ticket.tickets()).hasSize(1);
    }

    @Test
    void filesUploadKeepsExactOriginalBytesAndShaWithoutCompression() throws Exception {
        var draft = draftService.create(publicId, "20260001");
        byte[] original = image("jpg");
        String sha = sha256(original);

        var ticket = service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.FILES,
                        null, null, List.of(new RecruitmentUploadService.FileSpec(
                        "folder\\portrait\u0001.jpg", "image/jpeg", original.length, sha))));
        Long itemId = ticket.tickets().getFirst().itemId();
        String tempKey = jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE id=:id")
                .param("id", itemId).query(String.class).single();
        storage.put(tempKey, new ByteArrayInputStream(original), original.length, "image/jpeg");

        service.complete(publicId, draft.draftId(), ticket.batchId(), draft.draftToken());
        awaitBatch(ticket.batchId(), "SUCCEEDED");

        var stored = jdbc.sql("""
                SELECT original_file_name, object_key, size, sha256, temp_object_key
                FROM recruitment_upload_item WHERE id=:id
                """).param("id", itemId).query((rs, row) -> Map.of(
                        "name", rs.getString("original_file_name"),
                        "key", rs.getString("object_key"),
                        "size", rs.getLong("size"),
                        "sha", rs.getString("sha256"))).single();
        assertThat(stored.get("name")).isEqualTo("folder_portrait_.jpg");
        assertThat(stored.get("key").toString())
                .startsWith("recruitments/applications/" + draft.draftId() + "/");
        assertThat(storage.open(stored.get("key").toString()).readAllBytes()).isEqualTo(original);
        assertThat(stored.get("size")).isEqualTo((long) original.length);
        assertThat(stored.get("sha")).isEqualTo(sha);
        assertThat(attachmentReader.stateForDraft(draft.draftId()).attachments())
                .singleElement().satisfies(attachment -> {
                    assertThat(attachment.objectKey()).isEqualTo(stored.get("key"));
                    assertThat(attachment.sha256()).isEqualTo(sha);
                });
    }

    @Test
    void recruitmentExecutorIsBoundedSerialAndNeverRunsRejectedWorkOnCaller() {
        assertThat(recruitmentUploadExecutor.getCorePoolSize()).isEqualTo(1);
        assertThat(recruitmentUploadExecutor.getMaxPoolSize()).isEqualTo(1);
        var queue = recruitmentUploadExecutor.getThreadPoolExecutor().getQueue();
        assertThat(queue.size() + queue.remainingCapacity()).isEqualTo(100);
        assertThat(recruitmentUploadExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    void rejectsWrongTokenCrossDraftSecondBatchAndArchiveSizeMismatch() throws Exception {
        var firstDraft = draftService.create(publicId, "20260002");
        var secondDraft = draftService.create(publicId, "20260003");
        byte[] archive = zip(Map.of("photo.jpg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}));
        var ticket = service.create(publicId, firstDraft.draftId(), firstDraft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.ZIP,
                        "photos.zip", (long) archive.length + 1, null));

        assertThatThrownBy(() -> service.get(publicId, firstDraft.draftId(), ticket.batchId(), "wrong"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("凭证无效");
        assertThatThrownBy(() -> service.get(publicId, secondDraft.draftId(), ticket.batchId(),
                secondDraft.draftToken())).isInstanceOf(BusinessException.class)
                .hasMessageContaining("批次不存在");
        assertThatThrownBy(() -> service.create(publicId, firstDraft.draftId(),
                firstDraft.draftToken(), new RecruitmentUploadService.CreateBatch(
                        RecruitmentUploadMode.ZIP, "again.zip", 10L, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已有上传批次");

        String archiveKey = jdbc.sql("SELECT archive_object_key FROM recruitment_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single();
        storage.put(archiveKey, new ByteArrayInputStream(archive), archive.length, "application/zip");
        var failed = service.complete(publicId, firstDraft.draftId(), ticket.batchId(),
                firstDraft.draftToken());
        assertThat(failed.status()).isEqualTo(RecruitmentUploadBatchStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo(RecruitmentUploadMessages.ZIP_HEAD_INVALID);
        assertThat(jdbc.sql("SELECT status FROM recruitment_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single()).isEqualTo("FAILED");
        assertThat(storage.find(archiveKey)).isEmpty();
        assertThat(jdbc.sql("SELECT archive_object_key FROM recruitment_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single()).isEqualTo(archiveKey);
    }

    @Test
    void zipSkipsNonImagesAndFinalizesEachEntryAsOriginalBytes() throws Exception {
        var draft = draftService.create(publicId, "20260004");
        byte[] jpeg = image("jpg");
        byte[] png = image("png");
        byte[] archive = zip(Map.of(
                "folder/first.jpg", jpeg,
                "folder/second.png", png,
                "ignored.txt", new byte[] {8}));
        var ticket = service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.ZIP,
                        "images.zip", (long) archive.length, null));
        String archiveKey = jdbc.sql("SELECT archive_object_key FROM recruitment_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single();
        storage.put(archiveKey, new ByteArrayInputStream(archive), archive.length, "application/zip");

        service.complete(publicId, draft.draftId(), ticket.batchId(), draft.draftToken());
        awaitBatch(ticket.batchId(), "SUCCEEDED");

        var items = jdbc.sql("""
                SELECT original_file_name, object_key FROM recruitment_upload_item
                WHERE batch_id=:id ORDER BY original_file_name
                """).param("id", ticket.batchId()).query((rs, row) ->
                Map.entry(rs.getString(1), rs.getString(2))).list();
        assertThat(items).hasSize(2);
        for (var item : items) {
            byte[] expected = item.getKey().equals("first.jpg") ? jpeg : png;
            assertThat(storage.open(item.getValue()).readAllBytes()).isEqualTo(expected);
        }
    }

    @Test
    void fakeImageWithValidMagicFailsFullStructureValidationWithSafeReason() throws Exception {
        var expiredDraft = draftService.create(publicId, "20260005");
        byte[] fake = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1, 2, 3, 4};
        var failedTicket = createAndUploadFile(expiredDraft, "fake.jpg", "image/jpeg", fake);
        service.complete(publicId, expiredDraft.draftId(), failedTicket.batchId(),
                expiredDraft.draftToken());
        awaitBatch(failedTicket.batchId(), "FAILED");
        assertThat(jdbc.sql("SELECT object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", failedTicket.batchId()).query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT failure_reason FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", failedTicket.batchId()).query(String.class).single())
                .isEqualTo(RecruitmentUploadMessages.IMAGE_STRUCTURE_INVALID);
    }

    @Test
    void expiredDraftCleanupDeletesFinalButNeverSubmittedFinal() throws Exception {
        var expiredDraft = draftService.create(publicId, "20260015");
        byte[] valid = image("jpg");

        var cleanupTicket = createAndUploadFile(expiredDraft, "valid.jpg", "image/jpeg", valid);
        service.complete(publicId, expiredDraft.draftId(), cleanupTicket.batchId(),
                expiredDraft.draftToken());
        awaitBatch(cleanupTicket.batchId(), "SUCCEEDED");
        String expiredObject = objectKey(cleanupTicket.batchId());
        jdbc.sql("UPDATE recruitment_draft SET expires_at=:expiredAt WHERE id=:id")
                .param("expiredAt", justExpired()).param("id", expiredDraft.draftId()).update();

        var submittedDraft = draftService.create(publicId, "20260006");
        var submittedTicket = createAndUploadFile(submittedDraft, "keep.jpg", "image/jpeg", valid);
        service.complete(publicId, submittedDraft.draftId(), submittedTicket.batchId(),
                submittedDraft.draftToken());
        awaitBatch(submittedTicket.batchId(), "SUCCEEDED");
        String submittedObject = objectKey(submittedTicket.batchId());
        jdbc.sql("""
                UPDATE recruitment_draft SET status='SUBMITTED', expires_at=:expiredAt
                WHERE id=:id
                """).param("expiredAt", justExpired())
                .param("id", submittedDraft.draftId()).update();

        cleanupJob.cleanupExpiredDrafts();

        assertThat(storage.find(expiredObject)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM recruitment_draft WHERE id=:id")
                .param("id", expiredDraft.draftId()).query(String.class).single())
                .isEqualTo("EXPIRED");
        assertThat(storage.find(submittedObject)).isPresent();
        assertThat(jdbc.sql("SELECT status FROM recruitment_draft WHERE id=:id")
                .param("id", submittedDraft.draftId()).query(String.class).single())
                .isEqualTo("SUBMITTED");
    }

    @Test
    void expiredDraftWithOnlyZipArchiveRemainsACleanupCandidate() throws Exception {
        var draft = draftService.create(publicId, "20260018");
        byte[] archive = zip(Map.of("photo.jpg", image("jpg")));
        var ticket = service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.ZIP,
                        "pending.zip", (long) archive.length, null));
        String archiveKey = jdbc.sql("""
                SELECT archive_object_key FROM recruitment_upload_batch WHERE id=:id
                """).param("id", ticket.batchId()).query(String.class).single();
        storage.put(archiveKey, new ByteArrayInputStream(archive), archive.length,
                "application/zip");
        jdbc.sql("UPDATE recruitment_draft SET status='EXPIRED' WHERE id=:id")
                .param("id", draft.draftId()).update();

        var result = cleanupJob.cleanupExpiredDrafts();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(storage.find(archiveKey)).isEmpty();
        assertThat(jdbc.sql("SELECT archive_object_key FROM recruitment_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single()).isEqualTo(archiveKey);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(Long.class).single()).isZero();
    }

    @Test
    void expiredDraftWithOnlyFilesTemporaryObjectRemainsACleanupCandidate() throws Exception {
        var draft = draftService.create(publicId, "20260019");
        byte[] original = image("png");
        var ticket = createAndUploadFile(draft, "pending.png", "image/png", original);
        String tempKey = jdbc.sql("""
                SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id
                """).param("id", ticket.batchId()).query(String.class).single();
        jdbc.sql("UPDATE recruitment_draft SET status='EXPIRED' WHERE id=:id")
                .param("id", draft.draftId()).update();

        var result = cleanupJob.cleanupExpiredDrafts();

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(storage.find(tempKey)).isEmpty();
        assertThat(jdbc.sql("""
                SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id
                """).param("id", ticket.batchId()).query(String.class).single()).isEqualTo(tempKey);
        assertThat(jdbc.sql("SELECT object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).optional()).isEmpty();
    }

    @Test
    void replayedPresignedPutIsDeletedAfterExpiryEvenForSubmittedDraft() throws Exception {
        var draft = draftService.create(publicId, "20260007");
        byte[] original = image("png");
        var ticket = createAndUploadFile(draft, "replay.png", "image/png", original);
        String tempKey = jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).single();

        service.complete(publicId, draft.draftId(), ticket.batchId(), draft.draftToken());
        awaitBatch(ticket.batchId(), "SUCCEEDED");
        assertThat(storage.find(tempKey)).isEmpty();
        assertThat(jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).single()).isEqualTo(tempKey);

        // Simulate replay of the still-valid presigned PUT, then the later expiry reaper.
        storage.put(tempKey, new ByteArrayInputStream(original), original.length, "image/png");
        jdbc.sql("""
                UPDATE recruitment_upload_item SET upload_url_expires_at=:expiredAt
                WHERE batch_id=:id
                """).param("expiredAt", justExpired()).param("id", ticket.batchId()).update();
        jdbc.sql("UPDATE recruitment_draft SET status='SUBMITTED' WHERE id=:id")
                .param("id", draft.draftId()).update();

        temporaryCleanupJob.cleanupExpiredTargets();

        assertThat(storage.find(tempKey)).isEmpty();
        assertThat(jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).optional()).isEmpty();
        assertThat(storage.find(objectKey(ticket.batchId()))).isPresent();
    }

    @Test
    void expiryReaperPreservesProcessingSourceUntilProcessorCanRecover() throws Exception {
        var draft = draftService.create(publicId, "20260017");
        byte[] original = image("jpg");
        var ticket = createAndUploadFile(draft, "queued.jpg", "image/jpeg", original);
        String tempKey = jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).single();
        jdbc.sql("UPDATE recruitment_upload_batch SET status='PROCESSING' WHERE id=:id")
                .param("id", ticket.batchId()).update();
        jdbc.sql("""
                UPDATE recruitment_upload_item SET status='PROCESSING',
                    upload_url_expires_at=:expiredAt
                WHERE batch_id=:id
                """).param("expiredAt", justExpired()).param("id", ticket.batchId()).update();

        temporaryCleanupJob.cleanupExpiredTargets();

        assertThat(storage.find(tempKey)).isPresent();
        assertThat(jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).single()).isEqualTo(tempKey);
        processor.process(ticket.batchId());
        assertThat(jdbc.sql("SELECT status FROM recruitment_upload_batch WHERE id=:id")
                .param("id", ticket.batchId()).query(String.class).single()).isEqualTo("SUCCEEDED");
    }

    @Test
    void processorRecoversReservedFinalKeyAndZipItemsWithoutDuplicates() throws Exception {
        var fileDraft = draftService.create(publicId, "20260008");
        byte[] jpeg = image("jpg");
        var fileTicket = createAndUploadFile(fileDraft, "recover.jpg", "image/jpeg", jpeg);
        String reservedKey = "recruitments/applications/" + fileDraft.draftId() + "/reserved.jpg";
        jdbc.sql("UPDATE recruitment_upload_batch SET status='PROCESSING' WHERE id=:id")
                .param("id", fileTicket.batchId()).update();
        jdbc.sql("""
                UPDATE recruitment_upload_item SET status='PROCESSING', object_key=:key
                WHERE batch_id=:id
                """).param("key", reservedKey).param("id", fileTicket.batchId()).update();

        processor.process(fileTicket.batchId());

        assertThat(jdbc.sql("SELECT status FROM recruitment_upload_batch WHERE id=:id")
                .param("id", fileTicket.batchId()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(objectKey(fileTicket.batchId())).isEqualTo(reservedKey);
        assertThat(storage.open(reservedKey).readAllBytes()).isEqualTo(jpeg);

        var zipDraft = draftService.create(publicId, "20260009");
        byte[] png = image("png");
        byte[] archive = zip(Map.of("folder/recover.png", png));
        var zipTicket = service.create(publicId, zipDraft.draftId(), zipDraft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.ZIP,
                        "recover.zip", (long) archive.length, null));
        String archiveKey = jdbc.sql("SELECT archive_object_key FROM recruitment_upload_batch WHERE id=:id")
                .param("id", zipTicket.batchId()).query(String.class).single();
        storage.put(archiveKey, new ByteArrayInputStream(archive), archive.length, "application/zip");
        jdbc.sql("UPDATE recruitment_upload_batch SET status='PROCESSING' WHERE id=:id")
                .param("id", zipTicket.batchId()).update();
        jdbc.sql("""
                INSERT INTO recruitment_upload_item
                    (batch_id, original_file_name, content_type, size, sha256, status,
                     created_at, updated_at)
                VALUES (:batchId, 'recover.png', 'image/png', :size, :sha, 'PROCESSING',
                    :now, :now)
                """).param("batchId", zipTicket.batchId()).param("size", png.length)
                .param("now", now()).param("sha", sha256(png)).update();

        processor.process(zipTicket.batchId());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", zipTicket.batchId()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT status FROM recruitment_upload_batch WHERE id=:id")
                .param("id", zipTicket.batchId()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(storage.open(objectKey(zipTicket.batchId())).readAllBytes()).isEqualTo(png);
    }

    /** The same instant the service and the cleanup jobs read, whatever the host time zone is. */
    private LocalDateTime now() {
        return LocalDateTime.now(recruitmentClock);
    }

    /** A deadline that has just passed for the code under test. */
    private LocalDateTime justExpired() {
        return now().minusSeconds(1);
    }

    private RecruitmentUploadService.BatchTicket createAndUploadFile(
            RecruitmentDraftService.DraftTicket draft, String name, String type, byte[] bytes) {
        var ticket = service.create(publicId, draft.draftId(), draft.draftToken(),
                new RecruitmentUploadService.CreateBatch(RecruitmentUploadMode.FILES, null, null,
                        List.of(new RecruitmentUploadService.FileSpec(
                                name, type, bytes.length, sha256(bytes)))));
        String key = jdbc.sql("SELECT temp_object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", ticket.batchId()).query(String.class).single();
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type);
        return ticket;
    }

    private String objectKey(String batchId) {
        return jdbc.sql("SELECT object_key FROM recruitment_upload_item WHERE batch_id=:id")
                .param("id", batchId).query(String.class).single();
    }

    private void awaitBatch(String batchId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String current;
        do {
            current = jdbc.sql("SELECT status FROM recruitment_upload_batch WHERE id=:id")
                    .param("id", batchId).query(String.class).single();
            if (expected.equals(current)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("batch " + batchId + " did not reach " + expected
                + "; current=" + current);
    }

    private byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(3, 2,
                "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        image.setRGB(1, 0, Color.GREEN.getRGB());
        image.setRGB(2, 1, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void cleanFixture() {
        try {
            jdbc.sql("""
                    SELECT b.archive_object_key FROM recruitment_upload_batch b
                    JOIN recruitment_draft d ON d.id=b.draft_id WHERE d.task_id=:taskId
                      AND b.archive_object_key IS NOT NULL
                    """).param("taskId", TASK_ID).query(String.class).list().forEach(this::deleteObject);
            jdbc.sql("""
                    SELECT i.temp_object_key FROM recruitment_upload_item i
                    JOIN recruitment_upload_batch b ON b.id=i.batch_id
                    JOIN recruitment_draft d ON d.id=b.draft_id WHERE d.task_id=:taskId
                      AND i.temp_object_key IS NOT NULL
                    """).param("taskId", TASK_ID).query(String.class).list().forEach(this::deleteObject);
            jdbc.sql("""
                    SELECT i.object_key FROM recruitment_upload_item i
                    JOIN recruitment_upload_batch b ON b.id=i.batch_id
                    JOIN recruitment_draft d ON d.id=b.draft_id WHERE d.task_id=:taskId
                      AND i.object_key IS NOT NULL
                    """).param("taskId", TASK_ID).query(String.class).list().forEach(this::deleteObject);
            jdbc.sql("DELETE FROM recruitment_application WHERE task_id=:id")
                    .param("id", TASK_ID).update();
            jdbc.sql("""
                    DELETE FROM recruitment_upload_item WHERE batch_id IN
                      (SELECT b.id FROM recruitment_upload_batch b JOIN recruitment_draft d
                       ON d.id=b.draft_id WHERE d.task_id=:id)
                    """).param("id", TASK_ID).update();
            jdbc.sql("""
                    DELETE FROM recruitment_upload_batch WHERE draft_id IN
                      (SELECT id FROM recruitment_draft WHERE task_id=:id)
                    """).param("id", TASK_ID).update();
            jdbc.sql("DELETE FROM recruitment_draft WHERE task_id=:id")
                    .param("id", TASK_ID).update();
            jdbc.sql("DELETE FROM recruitment_task WHERE id=:id").param("id", TASK_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
        } catch (RuntimeException ignored) {
            // The first cleanup runs before the fixture tables contain matching rows.
        }
    }

    private void deleteObject(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Best-effort test fixture cleanup.
        }
    }
}

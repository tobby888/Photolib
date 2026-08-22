package cn.photolib.recruitment.upload;

import cn.photolib.common.upload.SafeImageZipExtractor;
import cn.photolib.photo.ImageCompressor;
import cn.photolib.photo.PhotoProcessingProperties;
import cn.photolib.photo.PhotoProcessingWorkspace;
import cn.photolib.storage.LocalObjectStorageService;
import cn.photolib.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecruitmentUploadProcessorRaceTests {
    private static final String BATCH_ID = "race-batch";
    private static final String DRAFT_ID = "race-draft";
    private static final String TEMP_KEY = "recruitments/temporary/race.jpg";

    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesFinalPutWhenExpiredCleanupClearedReservationBeforeSucceedCas() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<String> reservedKey = new AtomicReference<>();
        when(fixture.items.reserveObjectKey(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    reservedKey.set(invocation.getArgument(2));
                    return 1;
                });
        when(fixture.items.succeed(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), any())).thenReturn(0);
        when(fixture.items.selectById(1L)).thenAnswer(ignored -> item(
                RecruitmentUploadItemStatus.FAILED, null, fixture.bytes));

        fixture.processor.process(BATCH_ID);

        assertThat(reservedKey.get()).isNotBlank();
        assertThat(fixture.storage.find(reservedKey.get())).isEmpty();
        verify(fixture.items).clearReservedObjectKey(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(BATCH_ID),
                org.mockito.ArgumentMatchers.eq(reservedKey.get()),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void deletesFinalPutWhenSucceedTransactionThrowsAndLatestRowIsNotSuccessful() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<String> reservedKey = new AtomicReference<>();
        when(fixture.items.reserveObjectKey(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    reservedKey.set(invocation.getArgument(2));
                    return 1;
                });
        when(fixture.items.succeed(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), any())).thenThrow(new IllegalStateException("synthetic database failure"));
        when(fixture.items.selectById(1L)).thenAnswer(ignored -> item(
                RecruitmentUploadItemStatus.FAILED, null, fixture.bytes));

        fixture.processor.process(BATCH_ID);

        assertThat(reservedKey.get()).isNotBlank();
        assertThat(fixture.storage.find(reservedKey.get())).isEmpty();
    }

    @Test
    void preservesFinalPutWhenAnotherProcessorAlreadySucceededWithTheSameKey() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<String> reservedKey = new AtomicReference<>();
        when(fixture.items.reserveObjectKey(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    reservedKey.set(invocation.getArgument(2));
                    return 1;
                });
        when(fixture.items.succeed(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), any())).thenReturn(0);
        when(fixture.items.selectById(1L)).thenAnswer(ignored -> item(
                RecruitmentUploadItemStatus.SUCCEEDED, reservedKey.get(), fixture.bytes));

        fixture.processor.process(BATCH_ID);

        assertThat(reservedKey.get()).isNotBlank();
        assertThat(fixture.storage.open(reservedKey.get()).readAllBytes()).isEqualTo(fixture.bytes);
        verify(fixture.items, never()).clearReservedObjectKey(anyLong(), anyString(),
                anyString(), any());
    }

    @Test
    void preservesDurablyReservedPutWhileAnotherProcessorCanStillSucceed() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<String> reservedKey = new AtomicReference<>();
        when(fixture.items.reserveObjectKey(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    reservedKey.set(invocation.getArgument(2));
                    return 1;
                });
        when(fixture.items.succeed(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), any())).thenReturn(0);
        when(fixture.items.selectById(1L)).thenAnswer(ignored -> item(
                RecruitmentUploadItemStatus.PROCESSING, reservedKey.get(), fixture.bytes));

        fixture.processor.process(BATCH_ID);

        assertThat(fixture.storage.find(reservedKey.get())).isPresent();
        verify(fixture.items, never()).clearReservedObjectKey(anyLong(), anyString(),
                anyString(), any());
    }

    private Fixture fixture() throws Exception {
        RecruitmentUploadBatchMapper batches = mock(RecruitmentUploadBatchMapper.class);
        RecruitmentUploadItemMapper items = mock(RecruitmentUploadItemMapper.class);
        LocalObjectStorageService storage = storage();
        PhotoProcessingWorkspace workspace = new PhotoProcessingWorkspace(
                new PhotoProcessingProperties(1, temporaryDirectory.resolve("workspace").toString()));
        byte[] bytes = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4};
        storage.put(TEMP_KEY, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");

        RecruitmentUploadBatchEntity batch = new RecruitmentUploadBatchEntity();
        batch.setId(BATCH_ID);
        batch.setDraftId(DRAFT_ID);
        batch.setMode(RecruitmentUploadMode.FILES);
        batch.setStatus(RecruitmentUploadBatchStatus.PROCESSING);
        RecruitmentUploadItemEntity item = item(
                RecruitmentUploadItemStatus.PROCESSING, null, bytes);
        when(batches.selectById(BATCH_ID)).thenReturn(batch);
        when(items.selectList(any())).thenReturn(List.of(item));

        RecruitmentUploadProcessor processor = new RecruitmentUploadProcessor(
                batches, items, storage, workspace, mock(SafeImageZipExtractor.class),
                mock(ImageCompressor.class), directTransactions(),
                mock(RecruitmentUploadDispatchQueue.class));
        return new Fixture(processor, items, storage, bytes);
    }

    private RecruitmentUploadItemEntity item(RecruitmentUploadItemStatus status,
                                              String objectKey, byte[] bytes) {
        RecruitmentUploadItemEntity item = new RecruitmentUploadItemEntity();
        item.setId(1L);
        item.setBatchId(BATCH_ID);
        item.setOriginalFileName("race.jpg");
        item.setTempObjectKey(TEMP_KEY);
        item.setObjectKey(objectKey);
        item.setContentType("image/jpeg");
        item.setSize((long) bytes.length);
        item.setSha256(sha256(bytes));
        item.setStatus(status);
        return item;
    }

    private LocalObjectStorageService storage() {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null,
                temporaryDirectory.resolve("objects").toString(),
                "http://localhost/objects", "test-signing-secret", List.of(),
                Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofDays(1),
                10 * 1024 * 1024L, 100 * 1024 * 1024L, 0.6);
        return new LocalObjectStorageService(properties);
    }

    private TransactionTemplate directTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        return transactions;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(RecruitmentUploadProcessor processor,
                           RecruitmentUploadItemMapper items,
                           LocalObjectStorageService storage,
                           byte[] bytes) {
    }
}

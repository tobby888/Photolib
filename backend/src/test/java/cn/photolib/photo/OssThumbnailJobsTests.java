package cn.photolib.photo;

import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OssThumbnailJobsTests {
    private static final String TYPE = "image/jpeg";
    private static final String FINISHED = "photos/finished.jpg";
    private final OssAvifPreview encoder = mock(OssAvifPreview.class);
    private final PhotoMapper photos = mock(PhotoMapper.class);
    private final PreviewProfilePolicy profiles = mock(PreviewProfilePolicy.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final PreviewProfilePolicy.CommitPermit permit =
            new PreviewProfilePolicy.CommitPermit(PreviewProfile.ossAvif(), false, null);

    @TempDir Path temporary;
    private OssThumbnailJobs jobs;

    @AfterEach
    void stop() {
        if (jobs != null) jobs.stop();
    }

    @Test
    void usesAVirtualThreadAndTheValidatedBytesAndWaitsForTheFinishedKeyBeforeAttaching() throws Exception {
        start();
        Path input = Files.write(temporary.resolve("validated.jpg"), new byte[]{1, 3, 5});
        CountDownLatch conversionStarted = new CountDownLatch(1);
        CountDownLatch allowStaging = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            assertThat(allowStaging.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.getArgument(1, InputStream.class).readAllBytes()).containsExactly(1, 3, 5);
            return null;
        }).when(storage).put(startsWith("temporary/avif-previews/"), any(), eq(3L), eq(TYPE));
        when(encoder.generate(eq(42L), startsWith("temporary/avif-previews/"), eq(TYPE), eq(3L), anyString()))
                .thenAnswer(call -> {
                    conversionStarted.countDown();
                    return new OssAvifPreview.Thumbnail(call.getArgument(4), 731);
                });
        when(photos.attachCloudThumbnail(eq(42L), eq(FINISHED), anyString(), eq(731L), any()))
                .thenReturn(1);

        var pending = jobs.begin(42, input, TYPE, 3, permit);
        // The worker owns a stable link even after the native upload worker removes its path.
        Files.delete(input);
        allowStaging.countDown();
        assertThat(conversionStarted.await(5, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(photos);
        pending.publish(new OssThumbnailJobs.Source(FINISHED, TYPE, 2));
        pending.discard(); // The caller's finally block cannot undo a committed result.

        verify(photos, timeout(5000)).attachCloudThumbnail(
                eq(42L), eq(FINISHED), endsWith(".avif"), eq(731L), any());
        verify(encoder).delete(startsWith("temporary/avif-previews/"));
        verify(encoder, never()).delete(endsWith(".avif"));
    }

    @Test
    void oversizedOriginalUsesTheFinishedObjectWithoutRestagingTheInput() throws Exception {
        start();
        when(encoder.generate(eq(42L), eq(FINISHED), eq(TYPE), eq(12L), anyString()))
                .thenAnswer(call -> new OssAvifPreview.Thumbnail(call.getArgument(4), 731));
        when(photos.attachCloudThumbnail(eq(42L), eq(FINISHED), anyString(), eq(731L), any()))
                .thenReturn(1);
        var pending = jobs.begin(42, temporary.resolve("oversized.jpg"), TYPE,
                OssAvifPreview.MAX_SOURCE_BYTES + 1, permit);
        verifyNoInteractions(encoder, photos, storage);
        pending.publish(new OssThumbnailJobs.Source(FINISHED, TYPE, 12));

        verify(photos, timeout(5000)).attachCloudThumbnail(
                eq(42L), eq(FINISHED), endsWith(".avif"), eq(731L), any());
        verifyNoInteractions(storage);
    }

    @Test
    void discardsThePersistedPreviewWhenFinishedCompressionOrPublicationFails() throws Exception {
        start();
        Path input = Files.write(temporary.resolve("validated.jpg"), new byte[]{1});
        when(encoder.generate(eq(42L), startsWith("temporary/avif-previews/"), eq(TYPE), eq(1L), anyString()))
                .thenAnswer(call -> new OssAvifPreview.Thumbnail(call.getArgument(4), 731));

        jobs.begin(42, input, TYPE, 1, permit).discard();

        verify(encoder, timeout(5000)).delete(endsWith(".avif"));
        verifyNoInteractions(photos);
    }

    @Test
    void removesOutputWhenThePhotoWasReplacedOrAnotherJobAlreadyAttachedItsThumbnail() throws Exception {
        start();
        when(encoder.generate(eq(42L), eq(FINISHED), eq(TYPE), eq(12L), anyString()))
                .thenAnswer(call -> new OssAvifPreview.Thumbnail(call.getArgument(4), 731));
        jobs.begin(42, temporary.resolve("oversized.jpg"), TYPE,
                OssAvifPreview.MAX_SOURCE_BYTES + 1, permit)
                .publish(new OssThumbnailJobs.Source(FINISHED, TYPE, 12));

        verify(encoder, timeout(5000)).delete(endsWith(".avif"));
        verify(photos).attachCloudThumbnail(eq(42L), eq(FINISHED), anyString(), eq(731L), any());
    }

    @Test
    void refusesToAttachOutputUnderAChangedProfile() throws Exception {
        start();
        when(profiles.lockAndValidateForCommit(permit)).thenReturn(false);
        when(encoder.generate(eq(42L), eq(FINISHED), eq(TYPE), eq(12L), anyString()))
                .thenAnswer(call -> new OssAvifPreview.Thumbnail(call.getArgument(4), 731));
        jobs.begin(42, temporary.resolve("oversized.jpg"), TYPE,
                OssAvifPreview.MAX_SOURCE_BYTES + 1, permit)
                .publish(new OssThumbnailJobs.Source(FINISHED, TYPE, 12));

        verify(encoder, timeout(5000)).delete(endsWith(".avif"));
        verifyNoInteractions(photos);
    }

    private void start() {
        var workspace = new PhotoProcessingWorkspace(new PhotoProcessingProperties(1, temporary.toString()));
        jobs = new OssThumbnailJobs(encoder, photos, profiles, transactions, storage, workspace);
        when(profiles.lockAndValidateForCommit(permit)).thenReturn(true);
        when(transactions.execute(any())).thenAnswer(call ->
                call.getArgument(0, TransactionCallback.class).doInTransaction(mock(TransactionStatus.class)));
    }
}

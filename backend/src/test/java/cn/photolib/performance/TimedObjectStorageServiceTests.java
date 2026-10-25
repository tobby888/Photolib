package cn.photolib.performance;

import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 存储计时包装：关闭要转交给底下的实现，快照没开时什么都不记，开着时 open 在流关闭时按读出字节记一条。 */
class TimedObjectStorageServiceTests {

    @Test
    void closeIsForwardedSoTheOssClientIsShutDown() throws Exception {
        ObjectStorageService delegate = mock(ObjectStorageService.class,
                withSettings().extraInterfaces(AutoCloseable.class));
        new TimedObjectStorageService(delegate, mock(SnapshotRecorder.class)).close();
        verify((AutoCloseable) delegate).close();
    }

    @Test
    void passesThroughWithoutRecordingWhenNoSnapshotIsRunning() {
        ObjectStorageService delegate = mock(ObjectStorageService.class);
        SnapshotRecorder recorder = mock(SnapshotRecorder.class);
        when(recorder.active()).thenReturn(false);
        TimedObjectStorageService storage = new TimedObjectStorageService(delegate, recorder);

        storage.delete("a/b.jpg");

        verify(delegate).delete("a/b.jpg");
        verify(recorder, never()).storage(anyString(), anyString(), anyLong(), anyBoolean(), any(), any());
        assertThat(TimedObjectStorageService.unwrap(storage)).isSameAs(delegate);
    }

    @Test
    void openIsRecordedWhenTheStreamIsClosedWithTheBytesActuallyRead() throws Exception {
        ObjectStorageService delegate = mock(ObjectStorageService.class);
        SnapshotRecorder recorder = mock(SnapshotRecorder.class);
        when(recorder.active()).thenReturn(true);
        when(delegate.open("k")).thenReturn(new ByteArrayInputStream(new byte[1234]));
        TimedObjectStorageService storage = new TimedObjectStorageService(delegate, recorder);

        try (InputStream in = storage.open("k")) {
            verify(recorder, never()).storage(anyString(), anyString(), anyLong(), anyBoolean(), any(), any());
            assertThat(in.readAllBytes()).hasSize(1234);
        }

        verify(recorder).storage(eq("open"), eq("k"), anyLong(), eq(true), eq(1234L), eq(null));
    }
}

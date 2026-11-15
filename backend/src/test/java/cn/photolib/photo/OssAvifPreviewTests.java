package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.OssAccessMonitor;
import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OssAvifPreviewTests {
    private static final String SOURCE = "photos/source.jpg";
    private static final String KEY = OssAvifPreview.key("repair", 17);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final OssAccessMonitor monitor = mock(OssAccessMonitor.class);
    private final OssAvifPreview preview = new OssAvifPreview(storage, null, monitor);

    @Test
    void onlyReturnsPersistedAvifOfKnownSize() throws Exception {
        when(storage.stat(KEY)).thenReturn(new ObjectStorageService.ObjectInfo(213, "image/avif"));

        assertThat(preview.generate(17, SOURCE, "image/jpeg", 921, KEY))
                .isEqualTo(new OssAvifPreview.Thumbnail(KEY, 213));
        var sequence = inOrder(storage);
        sequence.verify(storage).saveAvifPreview(SOURCE, KEY);
        sequence.verify(storage).stat(KEY);
        verify(monitor).conversionSucceeded();
        verifyNoMoreInteractions(storage);
    }

    @Test
    void discardsUnexpectedMimeOrEmptyOutput() {
        when(storage.stat(KEY)).thenReturn(new ObjectStorageService.ObjectInfo(213, "image/jpeg"))
                .thenReturn(new ObjectStorageService.ObjectInfo(0, "image/avif"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> preview.generate(17, SOURCE, "image/jpeg", 921, KEY))
                    .hasMessageContaining("转存对象的类型或体积无效");
        }
        verify(storage, times(2)).delete(KEY);
        verifyNoInteractions(monitor);
    }

    @Test
    void unsupportedSourceSizeDoesNotCallOss() {
        assertThatThrownBy(() -> preview.generate(17, SOURCE, "image/jpeg",
                OssAvifPreview.MAX_SOURCE_BYTES + 1, KEY)).isInstanceOf(OssAvifPreview.UnusableImage.class);
        verifyNoInteractions(storage, monitor);
    }

    @Test
    void propagatesAccessErrorsSoProfileSwitchCannotDiscardHealthyOldPreviews() {
        OSSException forbidden = mock(OSSException.class);
        when(forbidden.getErrorCode()).thenReturn("AccessDenied");
        doThrow(forbidden).when(storage).saveAvifPreview(SOURCE, KEY);

        assertThatThrownBy(() -> preview.generate(17, SOURCE, "image/jpeg", 921, KEY)).isSameAs(forbidden);
        verify(monitor).conversionFailed(forbidden);
        verify(storage, never()).stat(KEY);
    }

    @Test
    void avifProfileDoesNotAcceptOldOrWronglyNamespacedObjects() {
        var object = new ObjectStorageService.ObjectInfo(213, "image/avif");
        assertThat(PreviewProfile.ossAvif().matchesStored(KEY, object)).isTrue();
        assertThat(PreviewProfile.ossAvif().matchesStored("thumbnails/old.avif", object)).isFalse();
        assertThat(PreviewProfile.configured(.6).matchesStored(KEY, object)).isFalse();
    }
}

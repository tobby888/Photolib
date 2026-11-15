package cn.photolib.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.comm.ResponseMessage;
import com.aliyun.oss.model.GenericResult;
import com.aliyun.oss.model.ProcessObjectRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AliyunAvifPreviewTests {
    private final OSS client = mock(OSS.class);
    private final OSS signer = mock(OSS.class);
    private final StorageProperties properties = mock(StorageProperties.class);
    private final AliyunObjectStorageService storage = new AliyunObjectStorageService(properties, client, signer);

    @Test
    void persistsTheSizedAvifUsingTheServerClientAndClosesTheResponse() throws Exception {
        when(properties.bucket()).thenReturn("private-bucket");
        ByteArrayInputStream json = spy(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        ResponseMessage response = mock(ResponseMessage.class);
        when(response.getContent()).thenReturn(json);
        GenericResult result = mock(GenericResult.class);
        when(result.getResponse()).thenReturn(response);
        when(client.processObject(any())).thenReturn(result);

        storage.saveAvifPreview("photo/source.jpg", "thumbnails/高清.avif");

        var request = ArgumentCaptor.forClass(ProcessObjectRequest.class);
        verify(client).processObject(request.capture());
        assertThat(request.getValue().getBucketName()).isEqualTo("private-bucket");
        assertThat(request.getValue().getKey()).isEqualTo("photo/source.jpg");
        String[] segments = request.getValue().getProcess().split("[|,]");
        assertThat(request.getValue().getProcess())
                .startsWith("image/resize,m_lfit,l_1600,s_1200,limit_1/format,avif|sys/saveas,o_");
        assertThat(decode(segments[segments.length - 2].substring(2))).isEqualTo("thumbnails/高清.avif");
        assertThat(decode(segments[segments.length - 1].substring(2))).isEqualTo("private-bucket");
        verify(json).close();
        verifyNoInteractions(signer);
    }

    @Test
    void neverOverwritesTheSourceImage() {
        assertThatThrownBy(() -> storage.saveAvifPreview("photo/same.avif", "photo/same.avif"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client, signer);
    }

    @Test
    void checksTheActualBucketAndPropagatesTheOssAuthenticationError() {
        when(properties.bucket()).thenReturn("private-bucket");
        OSSException rejected = new OSSException("expired");
        when(client.doesBucketExist("private-bucket")).thenReturn(true).thenThrow(rejected);

        storage.checkAccess();
        assertThatThrownBy(storage::checkAccess).isSameAs(rejected);
        verifyNoInteractions(signer);
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}

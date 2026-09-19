package cn.photolib.common.upload;

import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败原因写进 {@code failure_reason} 之后会一路回到上传者界面，**包括上传链接那条
 * 匿名通道**。所以这里钉住的是那条分界线：文件本身的毛病照原样说，基础设施的报错
 * 一个字都不往外抬。
 */
class UploadFailureMessageTests {
    private static final String FALLBACK = "没能处理完成，请稍后重试";

    @Test
    void theUploaderIsToldWhatIsWrongWithTheirOwnFile() {
        // 两条流水线里"上传者该看到的"那一类一律是 IllegalArgumentException。
        assertThat(UploadFailureMessage.forUploader(
                new IllegalArgumentException("ZIP 中没有 JPG/PNG 图片"), FALLBACK))
                .isEqualTo("ZIP 中没有 JPG/PNG 图片");
        assertThat(UploadFailureMessage.forUploader(
                new IllegalArgumentException("文件真实格式与声明类型不一致"), FALLBACK))
                .isEqualTo("文件真实格式与声明类型不一致");
        assertThat(UploadFailureMessage.isInternal(
                new IllegalArgumentException("图片超过 100 MiB"))).isFalse();
    }

    @Test
    void objectStorageInternalsNeverReachTheUploader() {
        // OSS 的异常原文带着 endpoint、RequestId 和对象键；站外访客拿到它没有任何
        // 用处，而 endpoint 本身按仓库的口径就是不该外流的东西。
        OSSException oss = new OSSException("Access denied", "AccessDenied", "REQ-1234567890",
                "photolib-prod.oss-cn-hangzhou.aliyuncs.com", null, null, null);
        assertThat(UploadFailureMessage.forUploader(oss, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(UploadFailureMessage.isInternal(oss)).isTrue();

        // 本地实现的异常原文带的是服务器路径，同样不外抬。
        IOException io = new IOException("读取本地对象失败: /srv/photolib/storage/temporary/x.zip");
        assertThat(UploadFailureMessage.forUploader(io, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(UploadFailureMessage.isInternal(io)).isTrue();
    }

    @Test
    void anEmptyMessageFallsBackInsteadOfShowingAnEmptyLine() {
        // 早先这里会退化成异常的类名（"ZipException"），对上传者同样是噪音。
        assertThat(UploadFailureMessage.forUploader(new IllegalArgumentException(), FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(UploadFailureMessage.forUploader(new IllegalArgumentException("  "), FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    void anOverlongReasonIsTruncatedToFitTheColumn() {
        String reason = "图".repeat(1_200);
        assertThat(UploadFailureMessage.forUploader(new IllegalArgumentException(reason), FALLBACK))
                .hasSize(1_000);
    }
}

package cn.photolib.common.upload;

import java.io.IOException;

/**
 * 原生图片组件明确判定"这张图本身处理不了"的失败，消息是能直接给上传者看的一句话。
 *
 * <p>仍然是 {@link IOException}：它从同一个原生调用里抛出，调用方原有的
 * {@code catch (IOException)} 与预览降级判定（{@code PreviewSourceUnusableException}）
 * 都照旧成立。区别只在于 {@link UploadFailureMessage} 会把它的消息原样给上传者，
 * 而不是换成通用提示——比如"超大渐进式 JPEG"，上传者只有知道这一点才能改成基线
 * JPEG 重新导出。</p>
 *
 * <p>只有原生层白名单里的那几种失败才包成这个类型（{@code NativeImageProcessor#failure}），
 * 其余原生错误仍是普通 {@link IOException}：libvips 的原文可能带着服务器路径。</p>
 */
public class UnprocessableImageException extends IOException {
    private final String uploaderMessage;

    public UnprocessableImageException(String uploaderMessage, String nativeDetail) {
        super(uploaderMessage + "（原生组件：" + nativeDetail + "）");
        this.uploaderMessage = uploaderMessage;
    }

    public String uploaderMessage() {
        return uploaderMessage;
    }
}

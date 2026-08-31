package cn.photolib.common.upload;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 直接作为文档发布的 PDF 的校验规则。
 *
 * <p>与 {@link InlineImageUpload} 的区别：那一套管的是正文里的插图（几百 KiB、
 * 读进内存再转存）。PDF 是整篇文档本身，可能有几十 MiB，所以这里<b>只读文件头</b>
 * 做校验，字节流原样转给对象存储，不进堆内存——一份 50 MiB 的 byte[] 乘上几个
 * 并发上传就足以让后端 OOM。</p>
 *
 * <p>声明的 Content-Type 一律不可信（完全由客户端提供），必须和文件头一致：
 * 只信它就等于让人把任意字节存成 {@code application/pdf} 再由服务端原样回吐，
 * 而回吐时浏览器是按我们声明的类型渲染的。</p>
 */
public final class PdfUpload {
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final String CONTENT_TYPE = "application/pdf";
    /** PDF 的文件头固定是 "%PDF-"。 */
    private static final byte[] SIGNATURE = {0x25, 0x50, 0x44, 0x46, 0x2d};

    private PdfUpload() {
    }

    /**
     * 校验大小、声明类型与文件头。通过之后调用方可以放心地把
     * {@link MultipartFile#getInputStream()} 直接交给对象存储。
     */
    public static void validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 PDF 文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "PDF 不能超过 " + ImageUploadPolicy.describe(MAX_BYTES));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith(CONTENT_TYPE)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "只支持 PDF 文件");
        }
        if (!matchesSignature(file)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "文件内容不是有效的 PDF");
        }
    }

    private static boolean matchesSignature(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(SIGNATURE.length);
            if (header.length < SIGNATURE.length) return false;
            for (int index = 0; index < SIGNATURE.length; index++) {
                if (header[index] != SIGNATURE[index]) return false;
            }
            return true;
        }
    }
}

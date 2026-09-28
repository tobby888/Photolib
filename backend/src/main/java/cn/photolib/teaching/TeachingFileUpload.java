package cn.photolib.teaching;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.upload.OfficeUpload;
import cn.photolib.teaching.model.TeachingMaterialFormat;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 教学资料上传的格式判定与校验：按文件头/内容嗅探出格式，再按格式走各自的校验。
 *
 * <p>格式不信任客户端声明（文件扩展名、Content-Type 都可伪造），只信任文件本身的字节：
 * {@code %PDF-} 是 PDF，{@code PK\x03\x04} 的 ZIP 按条目判定是 Word 还是 PPT。</p>
 */
final class TeachingFileUpload {
    private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04}; // PK\x03\x04
    private static final byte[] ZIP_EMPTY_SIGNATURE = {0x50, 0x4B, 0x05, 0x06}; // PK\x05\x06

    private TeachingFileUpload() {
    }

    /** 各格式的大小上限，由管理员在「上传限额」里设。 */
    record Limits(long pdfMaxBytes, long wordMaxBytes, long pptMaxBytes) {
        long largest() {
            return Math.max(pdfMaxBytes, Math.max(wordMaxBytes, pptMaxBytes));
        }
    }

    static TeachingMaterialFormat detectAndValidate(MultipartFile file, Limits limits) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择文件");
        }
        // 早筛：超过任何格式的上限就直接拒，别去读整个流。
        if (file.getSize() > limits.largest()) {
            throw tooLarge("文件", limits.largest());
        }
        byte[] head = head(file, 5);
        // 不走 PdfUpload.validate：它要求声明的 Content-Type 是 application/pdf、用的是文档中心的上限，
        // 而这里只认字节，PDF 的上限是教学资料自己那一项。
        if (startsWith(head, PDF_SIGNATURE)) {
            if (file.getSize() > limits.pdfMaxBytes()) throw tooLarge("PDF", limits.pdfMaxBytes());
            return TeachingMaterialFormat.PDF;
        }
        if (startsWith(head, ZIP_SIGNATURE) || startsWith(head, ZIP_EMPTY_SIGNATURE)) {
            OfficeUpload.Kind kind = OfficeUpload.detectKind(file);
            if (kind == OfficeUpload.Kind.WORD) {
                if (file.getSize() > limits.wordMaxBytes()) throw tooLarge("Word", limits.wordMaxBytes());
                return TeachingMaterialFormat.WORD;
            }
            if (file.getSize() > limits.pptMaxBytes()) throw tooLarge("PPT", limits.pptMaxBytes());
            return TeachingMaterialFormat.PPT;
        }
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                "仅支持 PDF、Word(.docx)、PPT(.pptx)");
    }

    private static BusinessException tooLarge(String label, long maxBytes) {
        return new BusinessException(ErrorCode.FILE_TOO_LARGE,
                label + "不能超过 " + ImageUploadPolicy.describe(maxBytes));
    }

    private static byte[] head(MultipartFile file, int length) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes(length);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) return false;
        }
        return true;
    }
}

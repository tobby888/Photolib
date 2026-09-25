package cn.photolib.teaching;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.OfficeUpload;
import cn.photolib.common.upload.PdfUpload;
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

    static TeachingMaterialFormat detectAndValidate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择文件");
        }
        // 早筛：超过任何格式的上限就直接拒，别去读整个流。
        if (file.getSize() > OfficeUpload.MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "文件不能超过 100 MiB");
        }
        byte[] head = head(file, 5);
        if (startsWith(head, PDF_SIGNATURE)) {
            PdfUpload.validate(file);
            return TeachingMaterialFormat.PDF;
        }
        if (startsWith(head, ZIP_SIGNATURE) || startsWith(head, ZIP_EMPTY_SIGNATURE)) {
            OfficeUpload.Kind kind = OfficeUpload.detectKind(file);
            if (kind == OfficeUpload.Kind.WORD && file.getSize() > OfficeUpload.WORD_MAX_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "Word 不能超过 20 MiB");
            }
            return kind == OfficeUpload.Kind.WORD
                    ? TeachingMaterialFormat.WORD : TeachingMaterialFormat.PPT;
        }
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                "仅支持 PDF、Word(.docx)、PPT(.pptx)");
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

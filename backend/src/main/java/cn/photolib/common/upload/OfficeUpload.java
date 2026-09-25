package cn.photolib.common.upload;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Word(.docx) / PPT(.pptx) 上传校验：只认 OOXML（本质是 ZIP 容器），不认旧版 .doc/.ppt。
 *
 * <p>不解析正文，只验三样：大小、ZIP 结构、必需条目（{@code [Content_Types].xml} 加
 * {@code word/} 或 {@code ppt/}）。声明的 Content-Type 不可信（浏览器常给
 * {@code application/octet-stream}），所以这里只看结构、按条目判定是 Word 还是 PPT。</p>
 */
public final class OfficeUpload {
    public static final long WORD_MAX_BYTES = 20L * 1024 * 1024;
    public static final long PPT_MAX_BYTES = 100L * 1024 * 1024;
    /** 上传入口的早筛上限：各格式上限的最大值（当前是 PPT 的 100 MiB）。 */
    public static final long MAX_BYTES = PPT_MAX_BYTES;

    public enum Kind {
        WORD,
        PPT
    }

    private OfficeUpload() {
    }

    /**
     * 判定一个 OOXML 文件是 Word 还是 PPT：扫描 ZIP 条目，看是 {@code word/} 还是 {@code ppt/}。
     * 两者都不是、或两者同时出现、或缺少 {@code [Content_Types].xml}，都按不支持拒绝。
     */
    public static Kind detectKind(MultipartFile file) throws IOException {
        boolean word = false;
        boolean ppt = false;
        boolean contentTypes = false;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("[Content_Types].xml".equals(name)) {
                    contentTypes = true;
                } else if (name.startsWith("word/")) {
                    word = true;
                } else if (name.startsWith("ppt/")) {
                    ppt = true;
                }
            }
        } catch (java.util.zip.ZipException | java.io.EOFException | IllegalArgumentException failure) {
            // 截断的包在读条目或解压时抛 EOFException，条目名编码坏了抛 IllegalArgumentException：
            // 都是文件本身坏了，不是服务端故障，不能落到兜底的 500。
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "文件内容不是有效的 Word/PPT 文件");
        }
        if (!contentTypes || word == ppt) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "文件内容不是有效的 Word/PPT 文件");
        }
        return word ? Kind.WORD : Kind.PPT;
    }
}

package cn.photolib.common.upload;

/**
 * 把处理失败的异常翻译成一句能给上传者看的话。
 *
 * <p>两条流水线（单张压缩、ZIP 解包）把失败原因写进数据库，而这个字段现在会一路回到
 * 界面上——**包括上传链接那条匿名通道**（`ProjectShareUploadService` 的状态接口）。
 * 直接存 {@code exception.getMessage()} 的问题是它分不清两类东西：</p>
 * <ul>
 *   <li><b>上传者该看到的</b>：文件本身的毛病。两条流水线里这一类一律是
 *       {@link IllegalArgumentException}——"图片超过 100 MiB"、"文件真实格式与声明类型
 *       不一致"、"ZIP 中没有 JPG/PNG 图片"、"ZIP 包含非法路径"……这些照原样给出去，
 *       上传者才知道下一步该做什么。</li>
 *   <li><b>上传者看不懂、也不该看到的</b>：对象存储 SDK 的异常原文带着 endpoint、
 *       RequestId 和对象键，本地实现的异常带着服务器路径。站内成员看了没用，站外
 *       访客更没有理由拿到（与 {@code src/storageUpload.ts} 同一条取舍：只有管理员
 *       用得上的线索留在日志里）。</li>
 * </ul>
 *
 * <p>按异常类型分而不是按文案匹配：文案会改，类型不会，而且改错了的时候它是
 * 向"少说"的方向失败。</p>
 */
public final class UploadFailureMessage {
    /** 与 {@code failure_reason} 列的长度对齐（VARCHAR(1000)），按 code point 截断。 */
    private static final int MAX_CODE_POINTS = 1000;

    private UploadFailureMessage() {
    }

    /** 上传者该看到的原因；不是文件本身的毛病时回 {@code fallback}。 */
    public static String forUploader(Throwable exception, String fallback) {
        if (!(exception instanceof IllegalArgumentException)) return fallback;
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return fallback;
        int[] codePoints = message.codePoints().limit(MAX_CODE_POINTS).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    /** {@link #forUploader} 回了 {@code fallback} 的那些，原文该进日志。 */
    public static boolean isInternal(Throwable exception) {
        return !(exception instanceof IllegalArgumentException)
                || exception.getMessage() == null || exception.getMessage().isBlank();
    }
}

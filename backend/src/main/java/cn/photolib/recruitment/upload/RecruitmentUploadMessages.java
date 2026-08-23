package cn.photolib.recruitment.upload;

/** Public/DB-safe failure texts. Internal exception details are logged only. */
final class RecruitmentUploadMessages {
    static final String ZIP_HEAD_INVALID = "ZIP 文件校验失败，请重新上传";
    static final String ZIP_INVALID = "ZIP 压缩包无效或不符合上传限制";
    static final String IMAGE_MISSING = "图片未上传或已失效";
    static final String IMAGE_SIZE_INVALID = "图片大小校验失败";
    static final String IMAGE_CONTENT_INVALID = "图片内容校验失败";
    static final String IMAGE_STRUCTURE_INVALID = "图片格式无效，仅支持完整的 JPG/PNG 图片";
    static final String IMAGE_INVALID = "图片校验失败，请检查文件后重试";
    static final String ALL_FAILED = "全部图片校验失败，请检查文件后重试";
    static final String PARTIAL_FAILED = "部分图片校验失败，请检查后重试";
    static final String DRAFT_EXPIRED = "招募草稿已过期，上传内容已清理";
    static final String UPLOAD_EXPIRED = "上传地址已过期，请重新上传";

    private RecruitmentUploadMessages() {
    }
}

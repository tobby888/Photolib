package cn.photolib.teaching.model;

/**
 * 教学资料的文件格式。
 *
 * <p>PDF 可在线预览；WORD / PPT 仅下载（浏览器原生无法预览 Office 文件）。
 * 扩展名与 Content-Type 用在这里拼对象键、下载文件名和响应头。</p>
 */
public enum TeachingMaterialFormat {
    PDF("pdf", "application/pdf"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PPT("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final String extension;
    private final String contentType;

    TeachingMaterialFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** 只有 PDF 有在线预览；Word/PPT 一律走下载。 */
    public boolean previewable() {
        return this == PDF;
    }
}

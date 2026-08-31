package cn.photolib.doc.model;

/**
 * 文档树的节点类型。FOLDER 只是容器；另外两种都是叶子，区别只在正文放在哪：
 * DOCUMENT 的正文是对象存储里的 Markdown，PDF 的"正文"就是上传的那份 PDF 文件。
 *
 * <p>发布与可见范围两个开关对两种叶子完全一致，判定也共用同一套代码——
 * 一份需要登录才能看的 PDF 和一篇需要登录才能看的 Markdown 文档，
 * 在读者眼里就该是同一件事。</p>
 */
public enum DocNodeType {
    FOLDER,
    DOCUMENT,
    PDF;

    public boolean isFolder() {
        return this == FOLDER;
    }
}

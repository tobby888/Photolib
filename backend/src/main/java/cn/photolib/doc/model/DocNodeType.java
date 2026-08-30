package cn.photolib.doc.model;

/** 文档树的节点类型。FOLDER 只是容器，正文只存在于 DOCUMENT 上。 */
public enum DocNodeType {
    FOLDER,
    DOCUMENT
}

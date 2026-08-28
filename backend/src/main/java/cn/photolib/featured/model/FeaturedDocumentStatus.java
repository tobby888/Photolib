package cn.photolib.featured.model;

public enum FeaturedDocumentStatus {
    /** 尚未截止，还没有可生成的定稿内容。 */
    PENDING,
    GENERATING,
    READY,
    FAILED
}

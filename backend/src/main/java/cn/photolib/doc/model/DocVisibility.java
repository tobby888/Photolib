package cn.photolib.doc.model;

/**
 * 一篇已发布文档的可见范围。与 {@code published} 正交，判定时两个条件都要满足。
 *
 * <p>枚举顺序即"从窄到宽"：{@link #MEMBERS} 是新文档的默认值，
 * 要把内容放到公网上必须有一次显式的操作。</p>
 */
public enum DocVisibility {
    /** 必须登录才能查看。 */
    MEMBERS,
    /** 未登录访客也能查看。 */
    PUBLIC
}

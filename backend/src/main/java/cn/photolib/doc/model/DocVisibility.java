package cn.photolib.doc.model;

/**
 * 一篇已发布文档的可见范围。与 {@code published} 正交，判定时两个条件都要满足。
 *
 * <p>{@link #MEMBERS} 是新文档的默认值，要把内容放到公网上必须有一次显式的操作。
 * 文件库的下载范围用的是同一个枚举和同一套判定（{@code DocAudience.allows}）。</p>
 */
public enum DocVisibility {
    /** 必须登录才能查看。 */
    MEMBERS,
    /** 未登录访客也能查看。 */
    PUBLIC,
    /**
     * 必须登录，并且属于其中一个指定权限组、或者是其中一位指定成员（Flyway V62）。
     * 名单在 {@code resource_access_grant} 里；名单为空的 RESTRICTED 谁也读不到，
     * 所以保存时要求至少指定一个组或一个人。
     */
    RESTRICTED
}

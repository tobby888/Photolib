package cn.photolib.mcp.model;

/**
 * 配对记录的状态。过期不是状态：它由 {@code expires_at} 判定，
 * 一条 {@code PENDING} 的记录放过 TTL 之后就不再可批准，不需要再写一次库。
 */
public enum McpAuthStatus {
    /** 已发起，等待成员在浏览器里批准。 */
    PENDING,
    /** 成员已批准，令牌还没被客户端取走。 */
    APPROVED,
    /** 成员拒绝了。客户端会看到明确的拒绝，而不是一直轮询到超时。 */
    DENIED,
    /** 令牌已被取走。一条记录只能换一次令牌。 */
    CONSUMED
}

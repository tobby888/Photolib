package cn.photolib.featured.model;

public enum FeaturedCloseReason {
    /** 部长手动截止。 */
    MANUAL,
    /** 到达截止时间后由 FeaturedDeadlineJob 关闭。 */
    DEADLINE
}

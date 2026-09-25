package cn.photolib.feedback.model;

/**
 * 反馈的工单状态。提交即 {@code PENDING}；ADMIN 改 {@code IN_PROGRESS} /
 * {@code RESOLVED}；{@code RESOLVED} 可重开回 {@code IN_PROGRESS}。
 */
public enum FeedbackStatus {
    PENDING,
    IN_PROGRESS,
    RESOLVED
}

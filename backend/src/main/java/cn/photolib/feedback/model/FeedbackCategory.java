package cn.photolib.feedback.model;

/**
 * 反馈的分类。首版只有「问题」与「建议」两类，值名与库里的 {@code VARCHAR} 一一对应
 * （对齐 {@code TeachingMaterialFormat} 的名值约定）。
 */
public enum FeedbackCategory {
    ISSUE,
    SUGGESTION
}

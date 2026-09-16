package cn.photolib.project.model;

/**
 * 选题的两种工作流程（issue #94）。
 *
 * <p>{@link #CREATION} 是改动之前唯一的流程：需求 → 接单 → 上传 → 标记被引。
 * {@link #EVENT} 针对大型活动——一次拍几千张、其中绝大多数用不上，为了不让它们
 * 长期占着 OSS，图片先整批进库，由选题创建者指定的选片人逐张打标签，
 * 打上 {@code deprecated} 的那些在选题完成后由人工确认后清理。</p>
 */
public enum ProjectType {
    /** 创作选题。 */
    CREATION,
    /** 活动选题。 */
    EVENT
}

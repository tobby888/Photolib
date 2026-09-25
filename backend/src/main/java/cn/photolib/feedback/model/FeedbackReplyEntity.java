package cn.photolib.feedback.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 反馈里的一条回复（提交人或 ADMIN）。追加型、不可编辑，所以没有 {@code version}。
 */
@Getter
@Setter
@TableName("feedback_reply")
public class FeedbackReplyEntity {
    @TableId
    private Long id;
    private Long feedbackId;
    private Long authorId;
    private String content;
    private String contentHtml;
    private LocalDateTime createdAt;

    /** 展示用，来自 join，不落库。 */
    @TableField(exist = false)
    private String authorDisplayName;
}

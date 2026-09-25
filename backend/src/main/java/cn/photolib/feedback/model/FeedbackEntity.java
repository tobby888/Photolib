package cn.photolib.feedback.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一条网站问题反馈（工单头）。刻意不继承 {@code BaseEntity}：这张表没有 {@code deleted}
 * 列——反馈不删除、不撤回、不归档，状态靠 {@code status} 表达而非软删。
 */
@Getter
@Setter
@TableName("feedback")
public class FeedbackEntity {
    @TableId
    private Long id;
    private Long submitterId;
    private String title;
    private String content;
    private String contentHtml;
    private FeedbackCategory category;
    private FeedbackStatus status;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 展示用，来自 join，不落库。 */
    @TableField(exist = false)
    private String submitterDisplayName;
}

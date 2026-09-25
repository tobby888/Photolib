package cn.photolib.feedback.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次状态流转。既供提交人时间线展示，也供审计追溯；追加型、不可编辑。
 */
@Getter
@Setter
@TableName("feedback_status_change")
public class FeedbackStatusChangeEntity {
    @TableId
    private Long id;
    private Long feedbackId;
    private FeedbackStatus fromStatus;
    private FeedbackStatus toStatus;
    private Long operatorId;
    private LocalDateTime createdAt;

    /** 展示用，来自 join，不落库。 */
    @TableField(exist = false)
    private String operatorDisplayName;
}

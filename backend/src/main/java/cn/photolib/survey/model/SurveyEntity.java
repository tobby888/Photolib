package cn.photolib.survey.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("survey")
public class SurveyEntity extends BaseEntity {
    private String title;
    /** 描述和简介都能被清空，默认的 NOT_NULL 更新策略会把置空悄悄丢掉。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String introMarkdown;
    private String formSchemaJson;
    /** 截止时间可以取消，同上。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime endsAt;
    private SurveyStatus status;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private Long closedBy;
    private LocalDateTime closedAt;

    @TableField(exist = false)
    private String creatorDisplayName;

    @TableField(exist = false)
    private Long targetCount;

    @TableField(exist = false)
    private Long responseCount;
}

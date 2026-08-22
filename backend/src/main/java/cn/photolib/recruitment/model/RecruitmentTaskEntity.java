package cn.photolib.recruitment.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("recruitment_task")
public class RecruitmentTaskEntity extends BaseEntity {
    private String publicId;
    private String title;
    private String introMarkdown;
    private String formSchemaJson;
    private String studentIdLabel;
    private String studentIdHelp;
    private String uploadLabel;
    private String uploadHelp;
    private Boolean uploadRequired;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private RecruitmentTaskStatus status;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private Long closedBy;
    private LocalDateTime closedAt;

    @TableField(exist = false)
    private String creatorDisplayName;

    @TableField(exist = false)
    private Long applicationCount;
}

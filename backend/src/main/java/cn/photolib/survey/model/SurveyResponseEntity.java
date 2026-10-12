package cn.photolib.survey.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("survey_response")
public class SurveyResponseEntity {
    @TableId
    private Long id;
    private Long surveyId;
    private Long userId;
    @JsonIgnore
    private String answersJson;
    @JsonIgnore
    private String formSchemaJson;
    /** 第一次提交的时间，修改答卷不会动它。 */
    private LocalDateTime submittedAt;
    /** 最后一次修改答卷的时间；没改过为 null。 */
    private LocalDateTime modifiedAt;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private String displayName;

    @TableField(exist = false)
    private String username;

    @TableField(exist = false)
    private String permissionGroupName;
}

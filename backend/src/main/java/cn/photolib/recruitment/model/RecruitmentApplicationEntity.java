package cn.photolib.recruitment.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("recruitment_application")
public class RecruitmentApplicationEntity {
    @TableId
    private String id;
    private Long taskId;
    private String draftId;
    private String studentId;
    private String normalizedStudentId;
    @JsonIgnore
    private String answersJson;
    @JsonIgnore
    private String formSchemaJson;
    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;
}

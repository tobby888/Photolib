package cn.photolib.recruitment.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("recruitment_draft")
public class RecruitmentDraftEntity {
    @TableId
    private String id;
    private Long taskId;
    private String normalizedStudentId;
    @JsonIgnore
    private String tokenHash;
    private RecruitmentDraftStatus status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

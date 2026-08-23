package cn.photolib.recruitment.upload;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("recruitment_upload_batch")
public class RecruitmentUploadBatchEntity {
    @TableId
    private String id;
    private String draftId;
    private RecruitmentUploadMode mode;
    private String archiveObjectKey;
    private String archiveFileName;
    private Long archiveSize;
    private LocalDateTime uploadUrlExpiresAt;
    private RecruitmentUploadBatchStatus status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failureCount;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

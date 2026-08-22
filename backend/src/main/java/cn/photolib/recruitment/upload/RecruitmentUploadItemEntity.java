package cn.photolib.recruitment.upload;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("recruitment_upload_item")
public class RecruitmentUploadItemEntity {
    @TableId
    private Long id;
    private String batchId;
    private String originalFileName;
    private String tempObjectKey;
    private LocalDateTime uploadUrlExpiresAt;
    private String objectKey;
    private String contentType;
    private Long size;
    private String sha256;
    private RecruitmentUploadItemStatus status;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

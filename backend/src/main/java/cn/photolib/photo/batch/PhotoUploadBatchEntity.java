package cn.photolib.photo.batch;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("photo_upload_batch")
public class PhotoUploadBatchEntity {
    @TableId private String id;
    private BatchMode mode;
    private Long requestId;
    private Long projectId;
    private Long createdBy;
    private String archiveObjectKey;
    private String archiveFileName;
    private Long archiveSize;
    private BatchStatus status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failureCount;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

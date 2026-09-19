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
    /** 这个批次是哪条上传链接开的；站内上传为 {@code null}（见 Flyway V49）。 */
    private Long shareLinkId;
    private String archiveObjectKey;
    private String archiveFileName;
    private Long archiveSize;
    private BatchStatus status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failureCount;
    private String failureReason;
    /** 压缩包直传地址的过期时间；清理任务据此判断"现在删安不安全"（Flyway V50）。 */
    private LocalDateTime uploadUrlExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

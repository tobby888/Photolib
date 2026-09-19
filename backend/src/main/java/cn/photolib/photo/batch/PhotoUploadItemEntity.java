package cn.photolib.photo.batch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("photo_upload_item")
public class PhotoUploadItemEntity {
    @TableId private Long id;
    private String batchId;
    private String originalFileName;
    private String tempObjectKey;
    @JsonIgnore private String tempLocalPath;
    private String contentType;
    private Long size;
    private String sha256;
    private String title;
    private String description;
    private String photographerStudentId;
    private String photographerName;
    private LocalDateTime takenAt;
    private String tagsJson;
    private BatchItemStatus status;
    private String failureReason;
    /** 条目直传地址的过期时间；清理任务据此判断"现在删安不安全"（Flyway V50）。 */
    private LocalDateTime uploadUrlExpiresAt;
    private Long photoId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

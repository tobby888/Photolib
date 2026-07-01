package cn.photolib.photo.batch;

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
    private Long photoId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

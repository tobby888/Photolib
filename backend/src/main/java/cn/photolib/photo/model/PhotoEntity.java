package cn.photolib.photo.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("photo")
public class PhotoEntity extends BaseEntity {
    private Long requestId;
    private Long projectId;
    private String title;
    private String description;
    private String photographerStudentId;
    private String photographerName;
    private Long uploadedBy;
    private Long campusId;
    private LocalDateTime takenAt;
    private String tagsJson;
    private Integer width;
    private Integer height;
    private Long size;
    private String contentType;
    private String objectKey;
    private String thumbnailObjectKey;
    private String originalObjectKey;
    private String storedFileName;
    private String sha256;
    private PhotoStatus status;
    private String failureReason;
    private LocalDateTime originalDeleteAfter;
}

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
    private Long thumbnailSize;
    private String originalObjectKey;
    private String storedFileName;
    private String sha256;
    private PhotoStatus status;
    private String failureReason;
    private LocalDateTime originalDeleteAfter;
    /** 经由哪条上传链接传进来的；站内上传为 {@code null}。 */
    private Long shareLinkId;
    /**
     * 这张照片的直传地址什么时候过期。只对还没 complete 的行有意义：
     * {@code AbandonedUploadCleanupJob} 要等它过期之后才敢删 {@code original_object_key}
     * 指着的那个临时对象，理由见 Flyway V50。
     */
    private LocalDateTime uploadUrlExpiresAt;
    /**
     * 停在 {@code PROCESSING} 之后被 {@code StalledProcessingRecoveryJob} 重新提交过几次
     * （Flyway V53）。重新 complete 时清零。
     */
    private Integer processingRecoveries;
}

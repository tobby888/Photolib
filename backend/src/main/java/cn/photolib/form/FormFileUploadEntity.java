package cn.photolib.form;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("form_file_upload")
public class FormFileUploadEntity {
    @TableId
    private String id;
    private FormFileOwnerType ownerType;
    private String ownerRef;
    private Long uploaderUserId;
    private String fieldId;
    private String fileName;
    private String contentType;
    private Long size;
    private String objectKey;
    private FormFileStatus status;
    private LocalDateTime uploadUrlExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime attachedAt;
}

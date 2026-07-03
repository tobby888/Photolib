package cn.photolib.notification;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("message_image")
public class MessageImageEntity {
    @TableId
    private String id;
    private String objectKey;
    private String contentType;
    private Long size;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}

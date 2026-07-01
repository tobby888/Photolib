package cn.photolib.notification;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("notification_log")
public class NotificationLogEntity {
    @TableId private Long id;
    private Long userId;
    private String email;
    private String eventType;
    private String status;
    private Integer retryCount;
    private String lastError;
    private String payloadJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

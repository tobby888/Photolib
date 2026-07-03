package cn.photolib.notification;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("user_notification")
public class UserNotificationEntity {
    @TableId
    private Long id;
    private Long userId;
    private String eventType;
    private String title;
    private String content;
    private String actionUrl;
    private Long senderId;
    private String contentHtml;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}

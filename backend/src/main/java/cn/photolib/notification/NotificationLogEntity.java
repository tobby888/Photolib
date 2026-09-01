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
    /** 投递通道：{@code WECOM} 是当前通道，{@code EMAIL} 只存在于 V38 之前的历史记录。 */
    private String channel;
    /** 该通道下的收件标识：企业微信 userid，或历史邮件记录里的邮箱。 */
    private String recipient;
    /** 历史邮件记录的收件邮箱，V38 起不再写入，仅供旧数据展示与重试。 */
    private String email;
    private String eventType;
    private String status;
    private Integer retryCount;
    private String lastError;
    private String payloadJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

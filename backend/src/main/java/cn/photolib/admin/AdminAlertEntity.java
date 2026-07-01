package cn.photolib.admin;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("admin_alert")
public class AdminAlertEntity {
    @TableId private Long id;
    private String type;
    private String message;
    private String resourceType;
    private String resourceId;
    private Boolean resolved;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private Long resolvedBy;
}

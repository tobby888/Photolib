package cn.photolib.audit;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("audit_log")
public class AuditLogEntity {
    @TableId private Long id;
    private Long operatorId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String requestId;
    private String detailJson;
    private String ipAddress;
    private LocalDateTime createdAt;
}

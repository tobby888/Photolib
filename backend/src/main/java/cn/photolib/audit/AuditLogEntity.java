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
    /** HTTP 方法。 */
    private String action;
    /** 给人看的操作名，来自接口上的 {@link AuditAction}。 */
    private String actionName;
    private String resourceType;
    private String resourceId;
    /** 操作对象当时的名字（选题标题、成员姓名……），记下来免得对象改名或删除后看不出是谁。 */
    private String resourceName;
    private String requestId;
    private Integer httpStatus;
    private Boolean success;
    private String errorCode;
    private String errorMessage;
    private String detailJson;
    private String ipAddress;
    private String userAgent;
    private Integer durationMs;
    private LocalDateTime createdAt;
}

package cn.photolib.audit;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AuditLogView {
    private Long id;
    private Long operatorId;
    private String operatorUsername;
    private String operatorDisplayName;
    /** 操作者现在的角色和权限组（不是操作当时的）。 */
    private String operatorRole;
    private String operatorGroupName;
    private String action;
    private String actionName;
    /** {@code actionName} 是按接口路径推断的（升级前的老记录），不是写日志时记下的。 */
    private boolean actionNameInferred;
    private String resourceType;
    /** 模块的中文名，由 {@link AuditResources} 补上。 */
    private String resourceLabel;
    private String resourceId;
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

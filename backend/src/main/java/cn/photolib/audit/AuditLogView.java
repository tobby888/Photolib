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
    private String action;
    private String resourceType;
    private String resourceId;
    private String requestId;
    private String detailJson;
    private String ipAddress;
    private LocalDateTime createdAt;
}

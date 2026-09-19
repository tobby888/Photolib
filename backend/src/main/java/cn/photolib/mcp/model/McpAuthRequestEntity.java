package cn.photolib.mcp.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("mcp_auth_request")
public class McpAuthRequestEntity {
    @TableId
    private Long id;
    private String requestId;
    private String deviceCodeHash;
    private String userCodeHash;
    private String clientName;
    private String deviceLabel;
    private String requestedIp;
    private McpAuthStatus status;
    private Long userId;
    private Integer failedAttempts;
    private LocalDateTime approvedAt;
    private LocalDateTime consumedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

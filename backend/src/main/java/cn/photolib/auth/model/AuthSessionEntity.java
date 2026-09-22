package cn.photolib.auth.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("auth_session")
public class AuthSessionEntity {
    @TableId
    private Long id;
    private Long userId;
    private String accessTokenHash;
    private String refreshTokenHash;
    private LocalDateTime accessExpiresAt;
    private LocalDateTime idleExpiresAt;
    private LocalDateTime revokedAt;
    /** 敏感操作再验证的信任期截止时间（Flyway V54）。 */
    private LocalDateTime stepUpUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package cn.photolib.auth.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("login_attempt")
public class LoginAttemptEntity {
    @TableId
    private Long id;
    private String scope;
    private String attemptKey;
    private Integer failureCount;
    private LocalDateTime firstFailedAt;
    private LocalDateTime lastFailedAt;
    private LocalDateTime lockedUntil;
}

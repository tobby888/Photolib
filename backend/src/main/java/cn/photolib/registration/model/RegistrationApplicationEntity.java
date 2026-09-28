package cn.photolib.registration.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 一份注册申请。审核通过前不建 app_user，见 V60 的说明。 */
@Getter
@Setter
@TableName("registration_application")
public class RegistrationApplicationEntity extends BaseEntity {
    private Long codeId;
    private String username;
    private String displayName;
    private String email;
    private String passwordHash;
    private RegistrationStatus status;
    /**
     * 只在待审核时有值，靠唯一索引挡住两份待审核申请抢同一个账号。审核后要能清成 NULL，
     * 默认的 NOT_NULL 更新策略会把 NULL 从 UPDATE 里丢掉，占位就永远摘不下来。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pendingUsername;
    /** 同 {@link #pendingUsername}。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pendingEmail;
    private Long reviewerId;
    private LocalDateTime reviewedAt;
    private String rejectReason;
    private Long userId;
}

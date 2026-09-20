package cn.photolib.user.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("app_user")
public class UserEntity extends BaseEntity {
    private String username;
    private String passwordHash;
    private String displayName;
    private UserRole role;
    private Long permissionGroupId;
    private Long campusId;
    /**
     * 和 wecomUserid 一样要能清空：默认的 NOT_NULL 更新策略会把 phone = NULL
     * 从 UPDATE 里丢掉，调用方把手机号置空后接口照样返回成功，库里那个号还在。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String phone;
    /**
     * 登录标识之一，且带 uk_user_email 唯一索引，所以必须能被「摘下来」：
     * 默认的 NOT_NULL 更新策略会把 email = NULL 从 UPDATE 里丢掉，
     * 管理员清空邮箱、把邮箱改挂到另一个账号、以及软删除时释放邮箱
     * 都会静默失败，那个邮箱从此谁也用不了。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String email;
    /** 企业微信通讯录里的 userid，通知投递的收件标识；未绑定时该用户只收站内信。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String wecomUserid;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarObjectKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarContentType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long avatarSize;
    private Boolean enabled;
    private Boolean mustChangePassword;
}

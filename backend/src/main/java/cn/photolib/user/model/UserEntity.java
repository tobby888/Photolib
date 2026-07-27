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
    private String phone;
    private String email;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarObjectKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String avatarContentType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long avatarSize;
    private Boolean enabled;
    private Boolean mustChangePassword;
}

package cn.photolib.user.model;

import cn.photolib.common.model.BaseEntity;
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
    private Long campusId;
    private String phone;
    private String email;
    private Boolean enabled;
    private Boolean mustChangePassword;
}

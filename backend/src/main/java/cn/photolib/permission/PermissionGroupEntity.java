package cn.photolib.permission;

import cn.photolib.auth.mfa.MfaPolicy;
import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("permission_group")
public class PermissionGroupEntity extends BaseEntity {
    private String code;
    private String name;
    private String description;
    private DataScope dataScope;
    private PhotoVisibility photoVisibility;
    private Boolean builtIn;
    private Boolean lowest;
    /** 两步验证策略（Flyway V54）。系统管理员组无论存的是什么都按强制处理。 */
    private MfaPolicy mfaPolicy;
}

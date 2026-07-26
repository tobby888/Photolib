package cn.photolib.permission;

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
    private Boolean builtIn;
    private Boolean lowest;
}

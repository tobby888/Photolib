package cn.photolib.campus.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("campus")
public class CampusEntity extends BaseEntity {
    private String code;
    private String name;
    private Boolean enabled;
}

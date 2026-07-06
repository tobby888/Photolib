package cn.photolib.directory;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("campus_member")
public class CampusMemberEntity extends BaseEntity {
    private Long campusId;
    private String studentId;
    private String name;
    private Boolean enabled;
}

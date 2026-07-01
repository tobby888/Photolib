package cn.photolib.project.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("project")
public class ProjectEntity extends BaseEntity {
    private String title;
    private String description;
    private ProjectStatus status;
    private Long createdBy;
}

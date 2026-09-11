package cn.photolib.project.model;

import cn.photolib.common.model.BaseEntity;
import cn.photolib.photo.PhotoTags;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@TableName("project")
public class ProjectEntity extends BaseEntity {
    private String title;
    private String description;
    private ProjectStatus status;
    private Long createdBy;
    private LocalDateTime completedAt;
    /** 预设标签的 JSON 数组；对外只暴露解析后的 {@link #getTags()}。 */
    @JsonIgnore
    private String tagsJson;

    /** 选题预设标签。为空表示不限制上传者使用的标签。 */
    @JsonProperty("tags")
    public List<String> getTags() {
        return PhotoTags.parse(tagsJson);
    }
}

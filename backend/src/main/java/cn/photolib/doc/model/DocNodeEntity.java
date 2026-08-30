package cn.photolib.doc.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("doc_node")
public class DocNodeEntity extends BaseEntity {
    private String publicId;
    private Long parentId;
    private DocNodeType nodeType;
    private String title;
    private Integer sortOrder;
    private Boolean published;
    private DocVisibility visibility;
    /** DOCUMENT 的正文对象键；从未保存过正文的文档为 null。 */
    private String objectKey;
    private Long contentSize;
    private String summary;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime publishedAt;

    @TableField(exist = false)
    private String updaterDisplayName;
}

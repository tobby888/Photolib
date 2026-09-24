package cn.photolib.teaching.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 一份教学资料。
 *
 * <p>文件本体在对象存储，数据库只留 {@code object_key} 与元数据。
 * {@code authorId}（作者，内容原作者）与 {@code createdBy}（上传人，把文件录入系统的管理者）
 * 是不同概念，见 CONTEXT.md。</p>
 */
@Getter
@Setter
@TableName("teaching_material")
public class TeachingMaterialEntity extends BaseEntity {
    private String publicId;
    private String title;
    private String description;
    private String category;
    /** 作者：内容原作者，从图库成员中选，可选。 */
    private Long authorId;
    private TeachingMaterialFormat format;
    private String objectKey;
    private Long contentSize;
    private Long downloadCount;
    /** 上传人：把文件录入系统的管理者，服务端自动记录。 */
    private Long createdBy;
    private Long updatedBy;

    /** 展示用，来自 join，不落库。 */
    @TableField(exist = false)
    private String authorDisplayName;
    @TableField(exist = false)
    private String uploaderDisplayName;
}

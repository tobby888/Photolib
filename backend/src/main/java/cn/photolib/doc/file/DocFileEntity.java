package cn.photolib.doc.file;

import cn.photolib.common.model.BaseEntity;
import cn.photolib.doc.model.DocVisibility;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 文件库里的一个文件（Flyway V62）。读者范围复用文档的三档 {@link DocVisibility}。 */
@Getter
@Setter
@TableName("doc_file")
public class DocFileEntity extends BaseEntity {
    private String publicId;
    private String title;
    /** 下载时给浏览器的文件名，已去掉路径和控制字符。 */
    private String fileName;
    /** 上传者声明的类型，只用于展示；对象存储里一律存成 application/octet-stream。 */
    private String contentType;
    private Long size;
    private String objectKey;
    private String description;
    private DocVisibility visibility;
    private Long downloadCount;
    private Long uploadedBy;
    private Long updatedBy;

    @TableField(exist = false)
    private String uploaderDisplayName;
}

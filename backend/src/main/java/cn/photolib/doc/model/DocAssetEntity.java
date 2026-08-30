package cn.photolib.doc.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("doc_asset")
public class DocAssetEntity {
    @TableId
    private String id;
    private Long nodeId;
    private String objectKey;
    private String contentType;
    private Long size;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}

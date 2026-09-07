package cn.photolib.admin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 管理员上传的缺图占位图：图片缺失、对象存储取不到或图片已被软删除时顶上去的那一张。 */
@Getter
@Setter
@TableName("branding_placeholder_image")
public class PlaceholderImageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileName;
    private byte[] image;
    private String imageContentType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

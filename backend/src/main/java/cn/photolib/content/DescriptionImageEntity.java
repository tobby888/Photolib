package cn.photolib.content;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("description_image")
public class DescriptionImageEntity {
    @TableId
    private String id;
    private String objectKey;
    private String contentType;
    private Long size;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}

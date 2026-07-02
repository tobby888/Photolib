package cn.photolib.admin;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("branding_setting")
public class BrandingSettingEntity {
    @TableId
    private Integer id;
    private String title;
    private String iconType;
    private String builtinIcon;
    private byte[] customIcon;
    private String customIconContentType;
    private String slogan;
    private LocalDateTime updatedAt;
}

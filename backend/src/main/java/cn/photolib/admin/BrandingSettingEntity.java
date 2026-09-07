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
    /** 登录页主标题，允许换行；空串表示这一项没配。 */
    private String loginHeadline;
    private String loginSubheadline;
    /** 登录页左侧的关键词，存 JSON 数组字符串。 */
    private String loginHighlights;
    private String loginNotice;
    private String footerText;
    /** 页脚链接，存 `[{"label":..,"url":..}]` 形式的 JSON 数组字符串。 */
    private String footerLinks;
    private LocalDateTime updatedAt;
}

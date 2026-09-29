package cn.photolib.dashboard.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 一位管理员的数据面板布局。没有软删除：见 V61 的说明。 */
@Getter
@Setter
@TableName("admin_dashboard_layout")
public class DashboardLayoutEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String layoutJson;
    @Version
    private Integer version;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package cn.photolib.admin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("branding_scheduled_icon")
public class ScheduledBrandIconEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cronExpression;
    private byte[] icon;
    private String iconContentType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package cn.photolib.adoption;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("adoption")
public class AdoptionEntity {
    @TableId private Long id;
    private Long projectId;
    private Long photoId;
    private String photographerStudentId;
    private String photographerName;
    private String remark;
    private Long adoptedBy;
    private LocalDateTime adoptedAt;
    @TableLogic private Boolean deleted;
    private LocalDateTime createdAt;
}

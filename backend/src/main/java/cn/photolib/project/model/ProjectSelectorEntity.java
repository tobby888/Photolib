package cn.photolib.project.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 活动选题的选片人。
 *
 * <p>刻意不继承 {@code BaseEntity}：这张表只表达集合成员关系，没有乐观锁也没有软删除。
 * 改选片人一律整组替换（同一事务里先删后插），软删除会让 {@code uk_project_selector}
 * 把「删掉又加回来」挡在外面。</p>
 */
@Getter
@Setter
@TableName("project_selector")
public class ProjectSelectorEntity {
    @TableId
    private Long id;
    private Long projectId;
    private Long userId;
    private Long createdBy;
    private LocalDateTime createdAt;
}

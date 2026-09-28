package cn.photolib.registration.model;

import cn.photolib.common.model.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 一枚注册码。数量、有效期和注册后的权限组都在生成时定下，见 V60 的说明。 */
@Getter
@Setter
@TableName("registration_code")
public class RegistrationCodeEntity extends BaseEntity {
    private String code;
    private String name;
    private Long permissionGroupId;
    private Integer maxUses;
    /** 名下「待审核 + 已通过」的申请数；提交时条件 UPDATE 占位，驳回时归还。 */
    private Integer usedCount;
    private LocalDateTime validFrom;
    /** 有效期右端，不含。 */
    private LocalDateTime validUntil;
    private Boolean enabled;
    private Long createdBy;
}

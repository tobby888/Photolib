package cn.photolib.request.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("request_participant")
public class RequestParticipantEntity {
    @TableId
    private Long id;
    private Long requestId;
    private Long userId;
    private LocalDateTime acceptedAt;
    private LocalDateTime createdAt;
}

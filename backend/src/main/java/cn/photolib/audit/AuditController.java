package cn.photolib.audit;

import cn.photolib.common.api.ApiResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private final AuditLogMapper mapper;

    @GetMapping
    ApiResponse<List<AuditLogEntity>> list(@RequestParam(required = false) Long operatorId,
                                           @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String resourceType) {
        return ApiResponse.ok(mapper.selectList(Wrappers.<AuditLogEntity>lambdaQuery()
                .eq(operatorId != null, AuditLogEntity::getOperatorId, operatorId)
                .eq(action != null, AuditLogEntity::getAction, action)
                .eq(resourceType != null, AuditLogEntity::getResourceType, resourceType)
                .orderByDesc(AuditLogEntity::getCreatedAt).last("LIMIT 500")));
    }
}

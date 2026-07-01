package cn.photolib.admin;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.model.RequestStatus;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.model.WorklogStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AdminController {
    private final AdminAlertMapper alertMapper;

    @GetMapping("/metadata/options")
    ApiResponse<Map<String, Object>> options() {
        return ApiResponse.ok(Map.of(
                "roles", UserRole.values(),
                "projectStatuses", ProjectStatus.values(),
                "requestStatuses", RequestStatus.values(),
                "photoStatuses", PhotoStatus.values(),
                "worklogStatuses", WorklogStatus.values(),
                "allowedImageTypes", List.of("image/jpeg", "image/png"),
                "singleImageMaxBytes", 104857600,
                "batchImageMaxCount", 100,
                "zipMaxBytes", 1500000000L,
                "batchDownloadMaxCount", 200
        ));
    }

    @GetMapping("/admin-alerts")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<AdminAlertEntity>> alerts(@RequestParam(defaultValue = "false") boolean resolved) {
        return ApiResponse.ok(alertMapper.selectList(Wrappers.<AdminAlertEntity>lambdaQuery()
                .eq(AdminAlertEntity::getResolved, resolved)
                .orderByDesc(AdminAlertEntity::getCreatedAt).last("LIMIT 500")));
    }

    @PostMapping("/admin-alerts/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> resolve(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        AdminAlertEntity alert = alertMapper.selectById(id);
        if (alert != null) {
            alert.setResolved(true);
            alert.setResolvedAt(LocalDateTime.now());
            alert.setResolvedBy(user.id());
            alertMapper.updateById(alert);
        }
        return ApiResponse.ok();
    }
}

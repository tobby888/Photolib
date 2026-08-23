package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recruitment-applications")
@RequiredArgsConstructor
public class RecruitmentApplicationController {
    private final RecruitmentApplicationService service;

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RECRUITMENT_VIEW')")
    ApiResponse<RecruitmentApplicationService.ApplicationDetail> get(
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }
}

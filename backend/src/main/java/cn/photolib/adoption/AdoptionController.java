package cn.photolib.adoption;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class AdoptionController {
    private final AdoptionService service;

    @PostMapping("/projects/{projectId}/adoptions")
    @PreAuthorize("hasAuthority('PROJECT_ADOPT')")
    ApiResponse<List<AdoptionEntity>> adopt(@PathVariable Long projectId,
                                            @Valid @RequestBody AdoptRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.adopt(projectId, request.photoIds(), request.remark(), user));
    }

    @GetMapping("/projects/{projectId}/adoptions")
    @PreAuthorize("hasAuthority('PROJECT_VIEW')")
    ApiResponse<PageResponse<AdoptionEntity>> list(@PathVariable Long projectId,
                                                   @RequestParam(defaultValue = "1") @Min(1) int page,
                                                   @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize,
                                                   @RequestParam(required = false) String photographerStudentId,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(projectId, page, pageSize, photographerStudentId, user));
    }

    @DeleteMapping("/projects/{projectId}/adoptions/{id}")
    @PreAuthorize("hasAuthority('PROJECT_ADOPT')")
    ApiResponse<Void> cancel(@PathVariable Long projectId, @PathVariable Long id,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        service.cancel(projectId, id, user);
        return ApiResponse.ok();
    }

    @GetMapping("/statistics/adoptions/ranking")
    @PreAuthorize("hasAuthority('STATISTICS_DOWNLOAD')")
    ApiResponse<List<AdoptionService.Ranking>> ranking(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long campusId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.ranking(from, to, projectId, campusId, user));
    }

    record AdoptRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> photoIds,
                        @Size(max = 500) String remark) {}
}

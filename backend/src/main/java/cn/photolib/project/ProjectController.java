package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<ProjectEntity> create(@Valid @RequestBody CreateRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(request.title(), request.description(), request.status(), user));
    }

    @GetMapping
    ApiResponse<PageResponse<ProjectEntity>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProjectStatus status,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, keyword, status, user));
    }

    @GetMapping("/{id}")
    ApiResponse<ProjectService.ProjectDetail> get(@PathVariable Long id,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.getDetail(id, user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<ProjectEntity> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.title(), request.description(), request.version(), user));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<ProjectEntity> status(@PathVariable Long id, @Valid @RequestBody StatusRequest request,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.changeStatus(id, request.status(), request.version(), user));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ProjectEntity> reopen(@PathVariable Long id, @Valid @RequestBody ReopenRequest request) {
        return ApiResponse.ok(service.reopen(id, request.version()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MINISTER')")
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    record CreateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                         @NotNull ProjectStatus status) {
    }

    record UpdateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                         @Min(1) int version) {
    }

    record StatusRequest(@NotNull ProjectStatus status, @Min(1) int version) {
    }

    record ReopenRequest(@NotBlank @Size(max = 500) String reason, @Min(1) int version) {
    }
}

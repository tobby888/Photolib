package cn.photolib.permission;

import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/permission-groups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PermissionGroupController {
    private final PermissionGroupService service;

    @GetMapping("/definitions")
    ApiResponse<List<PermissionGroupService.CategoryDefinition>> definitions() {
        return ApiResponse.ok(service.definitions());
    }

    @GetMapping
    ApiResponse<List<PermissionGroupService.GroupView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    ApiResponse<PermissionGroupService.GroupView> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    ApiResponse<PermissionGroupService.GroupView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(new PermissionGroupService.CreateCommand(
                request.code(), request.name(), request.description(), request.dataScope(),
                request.photoVisibility(), request.permissions())));
    }

    @PutMapping("/{id}")
    ApiResponse<PermissionGroupService.GroupView> update(@PathVariable Long id,
                                                         @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(id, new PermissionGroupService.UpdateCommand(
                request.name(), request.description(), request.dataScope(), request.photoVisibility(),
                request.permissions(), request.version())));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    record CreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{2,63}$") String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull DataScope dataScope,
            @NotNull PhotoVisibility photoVisibility,
            @NotNull Set<PermissionCode> permissions) {}

    record UpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull DataScope dataScope,
            @NotNull PhotoVisibility photoVisibility,
            @NotNull Set<PermissionCode> permissions,
            @Min(1) int version) {}
}

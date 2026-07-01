package cn.photolib.campus;

import cn.photolib.campus.model.CampusEntity;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/campuses")
@RequiredArgsConstructor
public class CampusController {
    private final CampusService service;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampusEntity> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request.code(), request.name()));
    }

    @GetMapping
    ApiResponse<List<CampusEntity>> list(@RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(service.list(enabled));
    }

    @GetMapping("/{id}")
    ApiResponse<CampusEntity> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampusEntity> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(id, request.name(), request.enabled(), request.version()));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampusEntity> enable(@PathVariable Long id) {
        CampusEntity campus = service.get(id);
        return ApiResponse.ok(service.update(id, campus.getName(), true, campus.getVersion()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<CampusEntity> disable(@PathVariable Long id) {
        CampusEntity campus = service.get(id);
        return ApiResponse.ok(service.update(id, campus.getName(), false, campus.getVersion()));
    }

    record CreateRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,32}$") String code,
                         @NotBlank @Size(max = 100) String name) {
    }

    record UpdateRequest(@NotBlank @Size(max = 100) String name, boolean enabled, @Min(1) int version) {
    }
}

package cn.photolib.directory;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/campus-members")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('DIRECTORY_VIEW','DIRECTORY_MANAGE','PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE','WORKLOG_SUBMIT')")
public class CampusMemberController {
    private final CampusMemberService service;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('DIRECTORY_VIEW','DIRECTORY_MANAGE','PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE','WORKLOG_SUBMIT')")
    ApiResponse<List<CampusMemberEntity>> list(@RequestParam(required = false) Long campusId,
                                               @RequestParam(required = false) Boolean enabled,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(campusId, enabled, user));
    }

    @GetMapping("/deduped")
    @PreAuthorize("hasAnyAuthority('DIRECTORY_VIEW','DIRECTORY_MANAGE','PHOTO_UPLOAD','REQUEST_PHOTO_MANAGE','WORKLOG_SUBMIT')")
    ApiResponse<List<CampusMemberService.DedupedMember>> deduped(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.listDeduped(user));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DIRECTORY_MANAGE')")
    ApiResponse<CampusMemberEntity> create(@Valid @RequestBody MemberRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(request.campusId(), request.studentId(), request.name(), user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DIRECTORY_MANAGE')")
    ApiResponse<CampusMemberEntity> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.studentId(), request.name(),
                request.enabled(), request.version(), user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DIRECTORY_MANAGE')")
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, user);
        return ApiResponse.ok();
    }

    record MemberRequest(Long campusId,
                         @NotBlank @Size(max = 64) String studentId,
                         @NotBlank @Size(max = 100) String name) {}

    record UpdateRequest(@NotBlank @Size(max = 64) String studentId,
                         @NotBlank @Size(max = 100) String name,
                         boolean enabled,
                         @Min(1) int version) {}
}

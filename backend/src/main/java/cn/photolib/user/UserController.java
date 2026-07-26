package cn.photolib.user;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.user.model.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserService.CreatedUser> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(userService.create(new UserService.CreateUser(
                request.username(), request.displayName(), request.role(), request.campusId(),
                request.phone(), request.email(), request.permissionGroupId(), request.campusIds())));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<PageResponse<UserService.UserView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Long permissionGroupId,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(userService.list(page, pageSize, keyword, role,
                permissionGroupId, campusId, enabled));
    }

    @GetMapping("/message-recipients")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ApiResponse<List<UserService.RecipientView>> messageRecipients() {
        return ApiResponse.ok(userService.messageRecipients());
    }

    @GetMapping("/campus-assignable")
    @PreAuthorize("hasAuthority('MANAGER_CAMPUS_ASSIGN')")
    ApiResponse<List<UserService.CampusAssignmentView>> campusAssignable(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(userService.campusAssignableUsers(user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id()")
    ApiResponse<UserService.UserView> get(@PathVariable Long id) {
        return ApiResponse.ok(userService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserService.UserView> update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(userService.update(id, new UserService.UpdateUser(
                request.displayName(), request.role(), request.campusId(), request.phone(),
                request.email(), request.enabled(), request.version(),
                request.permissionGroupId(), request.campusIds())));
    }

    @PutMapping("/{id}/authorization")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserService.UserView> updateAuthorization(
            @PathVariable Long id, @Valid @RequestBody AuthorizationRequest request) {
        return ApiResponse.ok(userService.updateAuthorization(id, request.permissionGroupId(),
                request.campusIds(), request.version()));
    }

    @PutMapping("/{id}/campus")
    @PreAuthorize("hasAuthority('MANAGER_CAMPUS_ASSIGN')")
    ApiResponse<UserService.UserView> updateCampus(
            @PathVariable Long id, @Valid @RequestBody UpdateCampusRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(userService.updateCampus(id, request.campusId(), request.version(), user));
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ResetPasswordResponse> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(new ResetPasswordResponse(userService.resetPassword(id)));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserService.UserView> enable(@PathVariable Long id) {
        return ApiResponse.ok(userService.setEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<UserService.UserView> disable(@PathVariable Long id) {
        return ApiResponse.ok(userService.setEnabled(id, false));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser current) {
        userService.delete(id, current.id());
        return ApiResponse.ok();
    }

    record CreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,64}$") String username,
            @NotBlank @Size(max = 100) String displayName,
            UserRole role,
            Long campusId,
            Long permissionGroupId,
            Set<@NotNull Long> campusIds,
            @Size(max = 32) String phone,
            @Email @Size(max = 255) String email) {
    }

    record UpdateRequest(
            @NotBlank @Size(max = 100) String displayName,
            UserRole role,
            Long campusId,
            Long permissionGroupId,
            Set<@NotNull Long> campusIds,
            @Size(max = 32) String phone,
            @Email @Size(max = 255) String email,
            boolean enabled,
            @Min(1) int version) {
    }

    record UpdateCampusRequest(@NotNull Long campusId, @Min(1) int version) {
    }

    record AuthorizationRequest(@NotNull Long permissionGroupId,
                                @NotNull Set<@NotNull Long> campusIds,
                                @Min(1) int version) {
    }

    record ResetPasswordResponse(String initialPassword) {
    }
}

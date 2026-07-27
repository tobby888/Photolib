package cn.photolib.user;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserAvatarController {
    private final UserAvatarService avatars;

    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    ApiResponse<AvatarResponse> replace(@RequestPart("file") MultipartFile file,
                                        @AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        return ApiResponse.ok(new AvatarResponse(avatars.replace(user.id(), file)));
    }

    @DeleteMapping("/me/avatar")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<AvatarResponse> delete(@AuthenticationPrincipal AuthenticatedUser user) {
        avatars.delete(user.id());
        return ApiResponse.ok(new AvatarResponse(null));
    }

    @GetMapping("/me/avatar")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> current(@AuthenticationPrincipal AuthenticatedUser user) {
        return avatar(user.id());
    }

    @GetMapping("/{id}/avatar")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> get(@PathVariable Long id) {
        return avatar(id);
    }

    private ResponseEntity<InputStreamResource> avatar(Long userId) {
        UserAvatarService.AvatarContent content = avatars.open(userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.size())
                .body(new InputStreamResource(content.input()));
    }

    record AvatarResponse(String avatarUrl) {
    }
}

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

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
    ResponseEntity<InputStreamResource> current(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(name = "v", required = false) Integer revision) {
        return avatar(user.id(), revision);
    }

    @GetMapping("/{id}/avatar")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> get(@PathVariable Long id,
                                            @RequestParam(name = "v", required = false) Integer revision) {
        return avatar(id, revision);
    }

    private ResponseEntity<InputStreamResource> avatar(Long userId, Integer revision) {
        UserAvatarService.AvatarContent content = avatars.open(userId);
        return ResponseEntity.ok()
                .cacheControl(cacheControl(content, revision))
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.size())
                .body(new InputStreamResource(content.input()));
    }

    /**
     * 地址里的 {@code v} 和当前头像版本对得上时，这条地址下的内容就再也不会变了：换头像
     * 会换版本号，也就换了地址（见 {@link UserAvatarService#avatarUrl}）。这时让浏览器
     * 一直用缓存，列表里每个头像就不必在每次挂载时都整张重下一遍。
     *
     * <p>对不上（前端拿着旧数据）或者没带 {@code v} 时仍是 {@code no-cache}：这条地址
     * 下的内容已经换过或还会再换，缓存住就会一直显示旧头像。两种都是 {@code private}，
     * 头像要登录才能看，不能让共享缓存存下来。
     */
    private static CacheControl cacheControl(UserAvatarService.AvatarContent content, Integer revision) {
        if (revision != null && revision == content.revision()) {
            return CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();
        }
        return CacheControl.noCache().cachePrivate();
    }

    record AvatarResponse(String avatarUrl) {
    }
}

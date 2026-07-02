package cn.photolib.admin;

import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class BrandingController {
    private static final int SETTING_ID = 1;
    private static final Set<String> ICONS = Set.of("camera", "aperture", "picture", "bulb", "star");
    private final BrandingSettingMapper mapper;

    @GetMapping("/branding")
    ApiResponse<BrandingResponse> get() {
        return ApiResponse.ok(toResponse(mapper.selectById(SETTING_ID)));
    }

    @PutMapping("/branding")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<BrandingResponse> update(@Valid @RequestBody BrandingRequest request) {
        if (!ICONS.contains(request.icon())) throw new IllegalArgumentException("不支持的面板图标");
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        if (setting == null) {
            setting = new BrandingSettingEntity();
            setting.setId(SETTING_ID);
            setting.setIcon(request.icon());
            setting.setSlogan(request.slogan().trim());
            mapper.insert(setting);
        } else {
            setting.setIcon(request.icon());
            setting.setSlogan(request.slogan().trim());
            mapper.updateById(setting);
        }
        return ApiResponse.ok(toResponse(setting));
    }

    private BrandingResponse toResponse(BrandingSettingEntity setting) {
        return setting == null
                ? new BrandingResponse("camera", "摄影工作站")
                : new BrandingResponse(setting.getIcon(), setting.getSlogan());
    }

    record BrandingRequest(
            @NotBlank @Pattern(regexp = "^[a-z]+$") String icon,
            @NotBlank @Size(max = 80) String slogan
    ) {}

    record BrandingResponse(String icon, String slogan) {}
}

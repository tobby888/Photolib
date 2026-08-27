package cn.photolib.admin;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class BrandingController {
    private static final int SETTING_ID = 1;
    private static final Set<String> ICONS = Set.of("camera", "aperture", "picture", "bulb", "star");
    private final BrandingSettingMapper mapper;
    private final BrandIconValidator iconValidator;
    private final ScheduledBrandIconService scheduledIconService;

    @GetMapping("/branding")
    ApiResponse<BrandingResponse> get() {
        return ApiResponse.ok(toResponse(mapper.selectById(SETTING_ID)));
    }

    @PutMapping("/branding")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<BrandingResponse> update(@Valid @RequestBody BrandingRequest request) {
        if (!Set.of("builtin", "custom").contains(request.iconType())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不支持的图标类型");
        }
        if (!ICONS.contains(request.builtinIcon())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不支持的系统图标");
        }
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        if (setting == null) {
            setting = new BrandingSettingEntity();
            setting.setId(SETTING_ID);
        }
        if ("custom".equals(request.iconType()) && setting.getCustomIcon() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请先上传自定义图标");
        }
        setting.setTitle(request.title().trim());
        setting.setIconType(request.iconType());
        setting.setBuiltinIcon(request.builtinIcon());
        setting.setSlogan(request.slogan().trim());
        setting.setUpdatedAt(LocalDateTime.now());
        if (mapper.selectById(SETTING_ID) == null) mapper.insert(setting); else mapper.updateById(setting);
        return ApiResponse.ok(toResponse(setting));
    }

    @PostMapping(value = "/branding/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<BrandingResponse> uploadIcon(@RequestPart("file") MultipartFile file) throws IOException {
        BrandIconValidator.NormalizedIcon normalized = iconValidator.normalize(file);
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        if (setting == null) {
            setting = defaults();
            setting.setId(SETTING_ID);
            setting.setCustomIcon(normalized.bytes());
            setting.setCustomIconContentType(normalized.contentType());
            setting.setIconType("custom");
            setting.setUpdatedAt(LocalDateTime.now());
            mapper.insert(setting);
        } else {
            setting.setCustomIcon(normalized.bytes());
            setting.setCustomIconContentType(normalized.contentType());
            setting.setIconType("custom");
            setting.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(setting);
        }
        return ApiResponse.ok(toResponse(setting));
    }

    @GetMapping("/branding/icon")
    ResponseEntity<byte[]> customIcon() {
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        if (setting == null || setting.getCustomIcon() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(setting.getCustomIconContentType()))
                .body(setting.getCustomIcon());
    }

    @GetMapping("/branding/scheduled-icons")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<ScheduledBrandIconService.ScheduledIconView>> scheduledIcons() {
        return ApiResponse.ok(scheduledIconService.list());
    }

    @PutMapping(value = "/branding/scheduled-icons", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<ScheduledBrandIconService.ScheduledIconView>> replaceScheduledIcons(
            @RequestPart("rules") List<ScheduledIconRuleRequest> rules,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        List<ScheduledBrandIconService.RuleInput> inputs = (rules == null ? List.<ScheduledIconRuleRequest>of() : rules)
                .stream().map(rule -> rule == null ? null : new ScheduledBrandIconService.RuleInput(
                        rule.id(), rule.cronExpression(), rule.fileIndex()))
                .toList();
        return ApiResponse.ok(scheduledIconService.replace(inputs, files));
    }

    @GetMapping("/branding/scheduled-icons/{id}/icon")
    ResponseEntity<byte[]> scheduledIcon(@PathVariable long id) {
        ScheduledBrandIconEntity icon = scheduledIconService.getIcon(id);
        if (icon == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(icon.getIconContentType()))
                .body(icon.getIcon());
    }

    private BrandingResponse toResponse(BrandingSettingEntity setting) {
        if (setting == null) setting = defaults();
        String customIconUrl = setting.getCustomIcon() == null ? null
                : "/api/v1/branding/icon?v=" + (setting.getUpdatedAt() == null ? 0 : setting.getUpdatedAt().hashCode());
        ZonedDateTime now = ZonedDateTime.now(ScheduledBrandIconService.SYSTEM_ZONE);
        LocalDate today = now.toLocalDate();
        ScheduledBrandIconEntity activeIcon = scheduledIconService.findActive(today);
        String displayIconType = activeIcon == null ? setting.getIconType() : "custom";
        String displayIconUrl = activeIcon == null ? customIconUrl
                : "/api/v1/branding/scheduled-icons/" + activeIcon.getId() + "/icon?v="
                + (activeIcon.getUpdatedAt() == null ? 0 : activeIcon.getUpdatedAt().hashCode());
        OffsetDateTime nextIconRefreshAt = today.plusDays(1)
                .atStartOfDay(ScheduledBrandIconService.SYSTEM_ZONE).toOffsetDateTime();
        return new BrandingResponse(setting.getTitle(), setting.getIconType(), setting.getBuiltinIcon(),
                customIconUrl, setting.getSlogan(), displayIconType, displayIconUrl, nextIconRefreshAt);
    }

    private BrandingSettingEntity defaults() {
        BrandingSettingEntity setting = new BrandingSettingEntity();
        setting.setTitle("摄影工作站");
        setting.setIconType("builtin");
        setting.setBuiltinIcon("camera");
        setting.setSlogan("影像协作平台");
        return setting;
    }

    record BrandingRequest(
            @NotBlank @Size(max = 40) String title,
            @NotBlank String iconType,
            @NotBlank String builtinIcon,
            @NotBlank @Size(max = 80) String slogan
    ) {}

    record BrandingResponse(String title, String iconType, String builtinIcon,
                            String customIconUrl, String slogan, String displayIconType,
                            String displayIconUrl, OffsetDateTime nextIconRefreshAt) {}

    record ScheduledIconRuleRequest(String id, String cronExpression, Integer fileIndex) {}
}

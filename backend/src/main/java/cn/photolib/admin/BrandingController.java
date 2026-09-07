package cn.photolib.admin;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class BrandingController {
    private static final int SETTING_ID = 1;
    private static final Set<String> ICONS = Set.of("camera", "aperture", "picture", "bulb", "star");
    private static final int MAX_HIGHLIGHTS = 6;
    private static final int MAX_FOOTER_LINKS = 6;
    // 只放行这几种前缀：页脚链接由管理员填、被所有角色点击，javascript: 之类的伪协议
    // 一旦落进 href 就是一个存储型 XSS。白名单比黑名单可靠。
    private static final List<String> ALLOWED_LINK_PREFIXES = List.of("https://", "http://", "mailto:", "#/");
    // 这个应用没有注册 ObjectMapper bean（AuditInterceptor 同样自带一个），
    // 而这里只是把两段配置读写成 JSON 字符串，默认配置就够用。
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final BrandingSettingMapper mapper;
    private final BrandIconValidator iconValidator;
    private final ScheduledBrandIconService scheduledIconService;
    private final PlaceholderImageService placeholderImageService;

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
        boolean isNew = setting == null;
        if (isNew) {
            setting = defaults();
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
        if (isNew) mapper.insert(setting); else mapper.updateById(setting);
        return ApiResponse.ok(toResponse(setting));
    }

    /**
     * 登录页文案和全站页脚。和品牌标识分成两个接口：后台是两块独立表单，
     * 合并成一个请求体会逼着任何一块的保存都把另一块的当前值一起回传，
     * 少传一个字段就是一次静默清空。
     */
    @PutMapping("/branding/site")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<BrandingResponse> updateSite(@Valid @RequestBody SiteContentRequest request) {
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        boolean isNew = setting == null;
        if (isNew) {
            setting = defaults();
            setting.setId(SETTING_ID);
        }
        setting.setLoginHeadline(trimmed(request.loginHeadline()));
        setting.setLoginSubheadline(trimmed(request.loginSubheadline()));
        setting.setLoginHighlights(writeJson(highlights(request.loginHighlights())));
        setting.setLoginNotice(trimmed(request.loginNotice()));
        setting.setFooterText(trimmed(request.footerText()));
        setting.setFooterLinks(writeJson(footerLinks(request.footerLinks())));
        setting.setUpdatedAt(LocalDateTime.now());
        if (isNew) mapper.insert(setting); else mapper.updateById(setting);
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

    @GetMapping("/branding/placeholder-images")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<PlaceholderImageService.PlaceholderImageView>> placeholderImages() {
        return ApiResponse.ok(placeholderImageService.list());
    }

    @PostMapping(value = "/branding/placeholder-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<PlaceholderImageService.PlaceholderImageView>> uploadPlaceholderImages(
            @RequestPart("files") List<MultipartFile> files) throws IOException {
        return ApiResponse.ok(placeholderImageService.add(files));
    }

    @DeleteMapping("/branding/placeholder-images/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<PlaceholderImageService.PlaceholderImageView>> deletePlaceholderImage(@PathVariable long id) {
        return ApiResponse.ok(placeholderImageService.remove(id));
    }

    @GetMapping("/branding/placeholder-images/{id}/image")
    ResponseEntity<byte[]> placeholderImage(@PathVariable long id) {
        PlaceholderImageEntity image = placeholderImageService.getImage(id);
        if (image == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.getImageContentType()))
                .body(image.getImage());
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
                customIconUrl, setting.getSlogan(), displayIconType, displayIconUrl, nextIconRefreshAt,
                nullToEmpty(setting.getLoginHeadline()), nullToEmpty(setting.getLoginSubheadline()),
                readHighlights(setting.getLoginHighlights()), nullToEmpty(setting.getLoginNotice()),
                nullToEmpty(setting.getFooterText()), readFooterLinks(setting.getFooterLinks()),
                placeholderImageService.imageUrls());
    }

    private BrandingSettingEntity defaults() {
        BrandingSettingEntity setting = new BrandingSettingEntity();
        setting.setTitle("摄影工作站");
        setting.setIconType("builtin");
        setting.setBuiltinIcon("camera");
        setting.setSlogan("影像协作平台");
        setting.setLoginHeadline("");
        setting.setLoginSubheadline("");
        setting.setLoginHighlights("[]");
        setting.setLoginNotice("");
        setting.setFooterText("");
        setting.setFooterLinks("[]");
        return setting;
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> highlights(List<String> requested) {
        List<String> values = new ArrayList<>();
        for (String value : requested == null ? List.<String>of() : requested) {
            String text = value == null ? "" : value.trim();
            if (text.isEmpty()) continue;
            if (text.length() > 12) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "登录页关键词不能超过 12 个字符");
            }
            values.add(text);
        }
        if (values.size() > MAX_HIGHLIGHTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "登录页关键词最多 " + MAX_HIGHLIGHTS + " 个");
        }
        return values;
    }

    private List<FooterLink> footerLinks(List<FooterLinkRequest> requested) {
        List<FooterLink> links = new ArrayList<>();
        for (FooterLinkRequest link : requested == null ? List.<FooterLinkRequest>of() : requested) {
            if (link == null) continue;
            String label = link.label() == null ? "" : link.label().trim();
            String url = link.url() == null ? "" : link.url().trim();
            if (label.isEmpty() && url.isEmpty()) continue;
            if (label.isEmpty() || url.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "页脚链接的名称和地址都要填写");
            }
            if (label.length() > 20) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "页脚链接名称不能超过 20 个字符");
            }
            if (url.length() > 200) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "页脚链接地址不能超过 200 个字符");
            }
            String normalized = url.toLowerCase(Locale.ROOT);
            if (ALLOWED_LINK_PREFIXES.stream().noneMatch(normalized::startsWith)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "页脚链接地址只支持 http://、https://、mailto: 或站内的 #/ 开头");
            }
            links.add(new FooterLink(label, url));
        }
        if (links.size() > MAX_FOOTER_LINKS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "页脚链接最多 " + MAX_FOOTER_LINKS + " 条");
        }
        return links;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "站点文案保存失败：" + exception.getMessage());
        }
    }

    // 库里的存量值不值得让整块品牌接口 500：解析不出来就当作没配过。
    private List<String> readHighlights(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<FooterLink> readFooterLinks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<FooterLink>>() {});
        } catch (IOException exception) {
            return List.of();
        }
    }

    record BrandingRequest(
            @NotBlank @Size(max = 40) String title,
            @NotBlank String iconType,
            @NotBlank String builtinIcon,
            @NotBlank @Size(max = 80) String slogan
    ) {}

    record SiteContentRequest(
            @Size(max = 120) String loginHeadline,
            @Size(max = 200) String loginSubheadline,
            List<String> loginHighlights,
            @Size(max = 200) String loginNotice,
            @Size(max = 300) String footerText,
            List<FooterLinkRequest> footerLinks
    ) {}

    record FooterLinkRequest(String label, String url) {}

    public record FooterLink(String label, String url) {}

    record BrandingResponse(String title, String iconType, String builtinIcon,
                            String customIconUrl, String slogan, String displayIconType,
                            String displayIconUrl, OffsetDateTime nextIconRefreshAt,
                            String loginHeadline, String loginSubheadline, List<String> loginHighlights,
                            String loginNotice, String footerText, List<FooterLink> footerLinks,
                            List<String> placeholderImageUrls) {}

    record ScheduledIconRuleRequest(String id, String cronExpression, Integer fileIndex) {}
}

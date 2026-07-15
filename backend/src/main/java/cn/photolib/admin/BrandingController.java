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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.Iterator;

@RestController
@RequiredArgsConstructor
public class BrandingController {
    private static final int SETTING_ID = 1;
    private static final long MAX_ICON_BYTES = 512 * 1024;
    private static final Set<String> ICONS = Set.of("camera", "aperture", "picture", "bulb", "star");
    private static final Set<String> IMAGE_TYPES = Set.of(MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE);
    private final BrandingSettingMapper mapper;

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
        if (mapper.selectById(SETTING_ID) == null) mapper.insert(setting); else mapper.updateById(setting);
        return ApiResponse.ok(toResponse(setting));
    }

    @PostMapping(value = "/branding/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<BrandingResponse> uploadIcon(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择图标文件");
        if (file.getSize() > MAX_ICON_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图标不能超过 512 KB");
        }
        if (!IMAGE_TYPES.contains(file.getContentType())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "图标仅支持 PNG 或 JPEG");
        }
        byte[] bytes = file.getBytes();
        BufferedImage image = readIcon(bytes);
        try (ByteArrayOutputStream normalized = new ByteArrayOutputStream()) {
            String format = MediaType.IMAGE_PNG_VALUE.equals(file.getContentType()) ? "png" : "jpeg";
            if (!ImageIO.write(image, format, normalized)) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法规范化图标图片");
            }
            bytes = normalized.toByteArray();
        }
        BrandingSettingEntity setting = mapper.selectById(SETTING_ID);
        if (setting == null) {
            setting = defaults();
            setting.setId(SETTING_ID);
            setting.setCustomIcon(bytes);
            setting.setCustomIconContentType(file.getContentType());
            setting.setIconType("custom");
            mapper.insert(setting);
        } else {
            setting.setCustomIcon(bytes);
            setting.setCustomIconContentType(file.getContentType());
            setting.setIconType("custom");
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

    private BrandingResponse toResponse(BrandingSettingEntity setting) {
        if (setting == null) setting = defaults();
        String customIconUrl = setting.getCustomIcon() == null ? null
                : "/api/v1/branding/icon?v=" + (setting.getUpdatedAt() == null ? 0 : setting.getUpdatedAt().hashCode());
        return new BrandingResponse(setting.getTitle(), setting.getIconType(), setting.getBuiltinIcon(),
                customIconUrl, setting.getSlogan());
    }

    private BufferedImage readIcon(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法识别图标图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) > 1024 || reader.getHeight(0) > 1024) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "图标尺寸不能超过 1024 × 1024 像素");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法识别图标图片");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private BrandingSettingEntity defaults() {
        BrandingSettingEntity setting = new BrandingSettingEntity();
        setting.setTitle("PhotoLib");
        setting.setIconType("builtin");
        setting.setBuiltinIcon("camera");
        setting.setSlogan("摄影工作站");
        return setting;
    }

    record BrandingRequest(
            @NotBlank @Size(max = 40) String title,
            @NotBlank String iconType,
            @NotBlank String builtinIcon,
            @NotBlank @Size(max = 80) String slogan
    ) {}

    record BrandingResponse(String title, String iconType, String builtinIcon,
                            String customIconUrl, String slogan) {}
}

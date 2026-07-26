package cn.photolib.content;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;

@RestController
@RequestMapping("/description-images")
@RequiredArgsConstructor
public class DescriptionImageController {
    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");

    private final DescriptionImageMapper mapper;
    private final ObjectStorageService storage;
    private final DescriptionImageAuthorizationService authorization;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('PROJECT_CREATE','REQUEST_CREATE')")
    ApiResponse<UploadResult> upload(@RequestPart("file") MultipartFile file,
                                     @AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "说明图片不能超过 5 MB");
        }
        String contentType = file.getContentType();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPEG、PNG 或 WebP 图片");
        }
        byte[] bytes = file.getBytes();
        if (!matchesSignature(bytes, contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "图片内容与文件类型不匹配");
        }

        String id = PublicId.next();
        String extension = switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> "jpg";
            case MediaType.IMAGE_PNG_VALUE -> "png";
            default -> "webp";
        };
        String objectKey = "descriptions/" + id + "." + extension;
        storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);

        DescriptionImageEntity image = new DescriptionImageEntity();
        image.setId(id);
        image.setObjectKey(objectKey);
        image.setContentType(contentType);
        image.setSize(file.getSize());
        image.setUploadedBy(user.id());
        image.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(image);
        } catch (RuntimeException exception) {
            storage.delete(objectKey);
            throw exception;
        }
        return ApiResponse.ok(new UploadResult("/api/v1/description-images/" + id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<InputStreamResource> get(@PathVariable String id,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        DescriptionImageEntity image = mapper.selectById(id);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        authorization.requireReadable(image, user);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .contentLength(image.getSize())
                .body(new InputStreamResource(storage.open(image.getObjectKey())));
    }

    record UploadResult(String url) {
    }

    private boolean matchesSignature(byte[] bytes, String type) {
        if (MediaType.IMAGE_JPEG_VALUE.equals(type)) {
            return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        }
        if (MediaType.IMAGE_PNG_VALUE.equals(type)) {
            byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            return bytes.length >= signature.length
                    && Arrays.equals(signature, Arrays.copyOf(bytes, signature.length));
        }
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}

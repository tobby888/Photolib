package cn.photolib.content;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.upload.InlineImageUpload;
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

@RestController
@RequestMapping("/description-images")
@RequiredArgsConstructor
public class DescriptionImageController {
    private final DescriptionImageMapper mapper;
    private final ObjectStorageService storage;
    private final DescriptionImageAuthorizationService authorization;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('PROJECT_CREATE','REQUEST_CREATE','FEATURED_MANAGE')")
    ApiResponse<UploadResult> upload(@RequestPart("file") MultipartFile file,
                                     @AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        byte[] bytes = InlineImageUpload.read(file);
        String contentType = file.getContentType();
        String id = PublicId.next();
        String objectKey = "descriptions/" + id + "." + InlineImageUpload.extension(contentType);
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
}

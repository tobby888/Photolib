package cn.photolib.storage;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.LimitedInputStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/local-storage/objects")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "photolib.storage", name = "mode", havingValue = "local")
public class LocalStorageController {
    private static final long MAX_ARCHIVE_BYTES = 1_500_000_000L;
    private final ObjectStorageService storage;
    private final StorageProperties properties;

    @PutMapping("/{token}")
    ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) throws IOException {
        LocalObjectStorageService local = local();
        LocalObjectStorageService.Token resolved = local.resolveToken(token, "PUT");
        String actualContentType = request.getContentType();
        String expectedContentType = resolved.contentType();
        if (expectedContentType != null && !expectedContentType.equals(actualContentType)) {
            throw new IllegalArgumentException("Content-Type 不匹配: 预期 " + expectedContentType + ", 实际 " + actualContentType);
        }
        long maximum = resolved.objectKey().endsWith("/archive.zip")
                ? MAX_ARCHIVE_BYTES : properties.imageMaxBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maximum) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "上传内容超过允许大小");
        }
        storage.put(resolved.objectKey(), new LimitedInputStream(request.getInputStream(), maximum), contentLength,
                actualContentType);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{token}")
    ResponseEntity<InputStreamResource> download(@PathVariable String token) {
        LocalObjectStorageService local = local();
        LocalObjectStorageService.Token resolved = local.resolveToken(token, "GET");
        ObjectStorageService.ObjectInfo info = storage.stat(resolved.objectKey());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentLength(info.size())
                .contentType(MediaType.parseMediaType(info.contentType()));
        if (resolved.downloadName() != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(resolved.downloadName(), StandardCharsets.UTF_8).build().toString());
        }
        return response.body(new InputStreamResource(storage.open(resolved.objectKey())));
    }

    private LocalObjectStorageService local() {
        if (storage instanceof LocalObjectStorageService local) return local;
        throw new IllegalStateException("本地对象存储未启用");
    }
}

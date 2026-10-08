package cn.photolib.doc.file;

import cn.photolib.audit.AuditInterceptor;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.doc.model.DocVisibility;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件库的上传与管理接口（读者接口在 {@link DocFileReaderController}）。
 *
 * <p>上传要 {@code FILE_UPLOAD}；改、删要 {@code FILE_UPLOAD}（只能动自己的）或
 * {@code FILE_MANAGE}（谁的都能动），"是不是自己的"由 {@link DocFileService} 判。
 * 路径第 3 段是资源类型、第 4 段是 id，审计拦截器按这个归档；上传时还没有 id，
 * 把新文件的 publicId、大小和下载范围补进审计详情。</p>
 */
@RestController
@RequestMapping("/doc-files")
@RequiredArgsConstructor
public class DocFileController {
    private final DocFileService service;

    /**
     * 上传。表单字段用 {@code @RequestParam}（multipart 的普通字段就是参数），只有文件本身是
     * {@code @RequestPart}。名单字段可以重复出现（{@code groupIds=1&groupIds=2}）。
     * 请求体大小在 {@code EndpointUploadLimitFilter} 早筛、全站 QPS 在 {@link DocFileQpsFilter} 早筛，
     * 都发生在 Spring 解析 multipart 之前。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('FILE_UPLOAD')")
    ApiResponse<DocFileService.FileView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @Size(max = DocFileService.MAX_TITLE) String title,
            @RequestParam(required = false) @Size(max = DocFileService.MAX_DESCRIPTION) String description,
            @RequestParam(required = false) DocVisibility visibility,
            @RequestParam(required = false) List<Long> groupIds,
            @RequestParam(required = false) List<Long> userIds,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest http) throws IOException {
        DocFileService.FileView created = service.upload(file, title, description, visibility,
                groupIds, userIds, user);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("publicId", created.publicId());
        audit.put("fileName", created.fileName());
        audit.put("size", created.size());
        audit.put("visibility", created.visibility());
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, audit);
        return ApiResponse.ok(created);
    }

    /** 上传者自己的用量：已用空间、今天传了几个，和管理员定的上限。 */
    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('FILE_UPLOAD')")
    ApiResponse<DocFileService.Usage> usage(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.usage(user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('FILE_UPLOAD','FILE_MANAGE')")
    ApiResponse<DocFileService.FileView> update(@PathVariable long id, @Valid @RequestBody UpdateRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser user,
                                                HttpServletRequest http) {
        DocFileService.FileView updated = service.update(id, request.title(), request.description(),
                request.visibility(), request.groupIds(), request.userIds(), request.version(), user);
        http.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of(
                "visibility", updated.visibility(),
                "readerGroups", updated.readerGroupIds().size(),
                "readerUsers", updated.readerUserIds().size()));
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('FILE_UPLOAD','FILE_MANAGE')")
    ApiResponse<Void> delete(@PathVariable long id, @RequestParam @Min(1) int version,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, version, user);
        return ApiResponse.ok();
    }

    record UpdateRequest(@NotBlank @Size(max = DocFileService.MAX_TITLE) String title,
                         @Size(max = DocFileService.MAX_DESCRIPTION) String description,
                         @NotNull DocVisibility visibility,
                         @Size(max = 50) Set<Long> groupIds,
                         @Size(max = 500) Set<Long> userIds,
                         @Min(1) int version) {
    }
}

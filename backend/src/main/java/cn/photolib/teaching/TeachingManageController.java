package cn.photolib.teaching;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import java.util.List;

/**
 * 教学资料的管理接口，整条面只认 {@code TEACHING_MANAGE}（默认管理员 + 部长）。
 *
 * <p>管理端点直接挂在 {@code /api/v1/teaching} 下（物品级操作是
 * {@code /api/v1/teaching/{id}}），读端点则在 {@link TeachingReaderController} 的
 * {@code /api/v1/teaching/materials} 下。这样分叉不是洁癖：审计拦截器按"路径第 3 段是
 * 资源类型、第 4 段是资源 id"归档，管理路径若再套一层 {@code /manage}，所有写操作的
 * 资源 id 都会落成 null。</p>
 *
 * <p>表单字段用 {@code @RequestParam}（multipart 的普通字段就是参数），只有文件本身是
 * {@code @RequestPart}——和文档中心的 PDF 上传保持同一套写法。</p>
 */
@RestController
@RequestMapping("/teaching")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TEACHING_MANAGE')")
public class TeachingManageController {
    private final TeachingService service;

    /** 作者下拉的候选项（全体图库成员）。 */
    @GetMapping("/authors")
    ApiResponse<List<TeachingService.AuthorOption>> authors() {
        return ApiResponse.ok(service.authorOptions());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<TeachingService.Material> create(
            @RequestParam @NotBlank @Size(max = 200) String title,
            @RequestParam(required = false) @Size(max = 1000) String description,
            @RequestParam @NotBlank @Size(max = 100) String category,
            @RequestParam(required = false) Long authorId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        return ApiResponse.ok(service.create(title, description, category, authorId, file, user));
    }

    /** 原地换文件：保留资料 id 与链接。 */
    @PutMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<TeachingService.Material> replaceFile(
            @PathVariable long id,
            @RequestParam @Min(1) int version,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        return ApiResponse.ok(service.replaceFile(id, file, version, user));
    }

    @PutMapping("/{id}")
    ApiResponse<TeachingService.Material> update(@PathVariable long id,
                                                 @Valid @RequestBody UpdateRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.updateMetadata(id, request.title(), request.description(),
                request.category(), request.authorId(), request.version(), user));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable long id, @RequestParam @Min(1) int version,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, version, user);
        return ApiResponse.ok();
    }

    /** 重命名分类：把这一类资料批量改到新名字。 */
    @PutMapping("/categories")
    ApiResponse<CategoryRenameResult> renameCategory(@Valid @RequestBody CategoryRenameRequest request) {
        return ApiResponse.ok(new CategoryRenameResult(
                service.renameCategory(request.from(), request.to())));
    }

    record UpdateRequest(@NotBlank @Size(max = 200) String title,
                         @Size(max = 1000) String description,
                         @NotBlank @Size(max = 100) String category,
                         Long authorId,
                         @Min(1) int version) {
    }

    record CategoryRenameRequest(@NotBlank @Size(max = 100) String from,
                                 @NotBlank @Size(max = 100) String to) {
    }

    record CategoryRenameResult(int updated) {
    }
}

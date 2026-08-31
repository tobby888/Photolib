package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.doc.model.DocNodeEntity;
import cn.photolib.doc.model.DocNodeType;
import cn.photolib.doc.model.DocVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * 文档中心的编辑接口。
 *
 * <p>整个控制器只有一条授权规则：{@code DOC_MANAGE}。读接口也要它——
 * 这里的树包含未发布的草稿和仅限成员的文档，读者要走的是
 * {@link DocReaderController}。</p>
 */
@RestController
@RequestMapping("/docs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('DOC_MANAGE')")
public class DocController {
    private final DocService service;

    @GetMapping("/tree")
    ApiResponse<List<DocService.ManageNode>> tree() {
        return ApiResponse.ok(service.tree());
    }

    @GetMapping("/{id}")
    ApiResponse<DocService.DocumentDetail> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    ApiResponse<DocService.TreeMutation> create(@Valid @RequestBody CreateRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(request.parentId(), request.nodeType(),
                request.title(), user));
    }

    @PutMapping("/{id}/title")
    ApiResponse<DocService.TreeMutation> rename(@PathVariable long id,
                                                @Valid @RequestBody RenameRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.rename(id, request.title(), request.version(), user));
    }

    @PutMapping("/{id}/content")
    ApiResponse<DocService.DocumentDetail> saveContent(@PathVariable long id,
                                                       @Valid @RequestBody ContentRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.saveContent(id, request.content(), request.version(), user));
    }

    @PostMapping("/{id}/publication")
    ApiResponse<DocService.TreeMutation> setPublished(@PathVariable long id,
                                                      @Valid @RequestBody PublicationRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.setPublished(id, request.published(), request.version(), user));
    }

    @PostMapping("/{id}/visibility")
    ApiResponse<DocService.TreeMutation> setVisibility(@PathVariable long id,
                                                       @Valid @RequestBody VisibilityRequest request,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.setVisibility(id, request.visibility(), request.version(), user));
    }

    @PostMapping("/{id}/move")
    ApiResponse<DocService.TreeMutation> move(@PathVariable long id,
                                              @Valid @RequestBody MoveRequest request,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.move(id, request.parentId(), request.index(),
                request.version(), user));
    }

    @DeleteMapping("/{id}")
    ApiResponse<DocService.TreeMutation> delete(@PathVariable long id,
                                                @RequestParam @Min(1) int version,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.delete(id, version, user));
    }

    /**
     * 直接上传一份 PDF 作为文档。走 multipart 而不是"先建节点再上传文件"两步：
     * 中途失败会留下一个没有文件的 PDF 节点，它既发布不了也预览不了。
     * 表单字段用 {@code @RequestParam}（multipart 的普通字段就是参数），
     * 只有文件本身是 {@code @RequestPart}。
     */
    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<DocService.TreeMutation> createPdf(@RequestParam(required = false) Long parentId,
                                                   @RequestParam @NotBlank @Size(max = 200) String title,
                                                   @RequestPart("file") MultipartFile file,
                                                   @AuthenticationPrincipal AuthenticatedUser user)
            throws IOException {
        return ApiResponse.ok(service.createPdf(parentId, title, file, user));
    }

    /** 换掉一份 PDF 文档的文件。对象键跟着 publicId 走，所以读者手上的链接继续有效。 */
    @PutMapping(value = "/{id}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<DocService.TreeMutation> replacePdf(@PathVariable long id,
                                                    @RequestParam @Min(1) int version,
                                                    @RequestPart("file") MultipartFile file,
                                                    @AuthenticationPrincipal AuthenticatedUser user)
            throws IOException {
        return ApiResponse.ok(service.replacePdf(id, file, version, user));
    }

    /**
     * 编辑器里预览 PDF。刻意不复用读者接口：草稿还没发布，读者接口对它一律 404，
     * 而作者必须能在发布之前看一眼自己传上去的是不是那份文件。
     */
    @GetMapping("/{id}/file")
    ResponseEntity<InputStreamResource> file(@PathVariable long id) {
        DocNodeEntity node = service.managedPdf(id);
        return DocPdfResponse.of(node, service.openNode(node));
    }

    @PostMapping(value = "/{id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<DocService.AssetUploaded> uploadAsset(@PathVariable long id,
                                                      @RequestPart("file") MultipartFile file,
                                                      @AuthenticationPrincipal AuthenticatedUser user)
            throws IOException {
        return ApiResponse.ok(service.uploadAsset(id, file, user));
    }

    /** 只能建 FOLDER 或 DOCUMENT；PDF 必须带着文件走 {@code POST /docs/pdf}。 */
    record CreateRequest(Long parentId, @NotNull DocNodeType nodeType,
                         @NotBlank @Size(max = 200) String title) {
    }

    record RenameRequest(@NotBlank @Size(max = 200) String title, @Min(1) int version) {
    }

    /**
     * 正文允许为空字符串（新建后先占位再写），所以只有长度上限，没有 {@code @NotBlank}。
     * 上限和 {@link DocService#MAX_CONTENT_CHARS} 是同一个数，改一处必须改另一处。
     */
    record ContentRequest(@Size(max = DocService.MAX_CONTENT_CHARS) String content,
                          @Min(1) int version) {
    }

    record PublicationRequest(boolean published, @Min(1) int version) {
    }

    /** PUBLIC = 未登录也能看，MEMBERS = 必须登录。与发布状态互不影响。 */
    record VisibilityRequest(@NotNull DocVisibility visibility, @Min(1) int version) {
    }

    /** {@code parentId} 为 null 表示移到根目录；{@code index} 是目标父节点下的位置。 */
    record MoveRequest(Long parentId, @Min(0) int index, @Min(1) int version) {
    }
}

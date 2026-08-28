package cn.photolib.featured;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.featured.model.FeaturedCollectionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 好图精选。
 *
 * <p>控制器上的注解只表达"查看/下载不设限"这一条：读接口全部是
 * {@code isAuthenticated()}。发布、编辑、删除、手动截止需要 {@code FEATURED_MANAGE}，
 * 填报条目需要被指派——这两类判断都在 Service 里做，因为它们还要看精选状态、
 * 时间窗口和图片可见范围，注解表达不了。</p>
 */
@RestController
@RequestMapping("/featured-collections")
@RequiredArgsConstructor
public class FeaturedCollectionController {
    private final FeaturedCollectionService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    ApiResponse<PageResponse<FeaturedCollectionService.CollectionView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) FeaturedCollectionStatus status,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.list(page, pageSize, keyword, status, user));
    }

    @GetMapping("/assignable-managers")
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<List<cn.photolib.user.UserService.CampusAssignmentView>> assignableManagers(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.assignableManagers(user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<FeaturedCollectionService.CollectionView> get(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.get(id, user));
    }

    @GetMapping("/{id}/entries")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<List<FeaturedCollectionService.EntryView>> entries(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.entries(id, user));
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<FeaturedCollectionService.DocumentDownload> document(@PathVariable long id) {
        return ApiResponse.ok(service.document(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<FeaturedCollectionService.CollectionView> create(
            @Valid @RequestBody CollectionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.create(request.toCommand(), user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<FeaturedCollectionService.CollectionView> update(
            @PathVariable long id, @Valid @RequestBody UpdateCollectionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.update(id, request.toCommand(), request.version(), user));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<FeaturedCollectionService.CollectionView> publish(
            @PathVariable long id, @Valid @RequestBody VersionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.publish(id, request.version(), user));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<FeaturedCollectionService.CollectionView> close(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.close(id, user));
    }

    @PostMapping("/{id}/document")
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<FeaturedCollectionService.CollectionView> regenerate(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.regenerateDocument(id, user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('FEATURED_MANAGE')")
    ApiResponse<Void> delete(@PathVariable long id, @RequestParam @Min(1) int version,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        service.delete(id, version, user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/entries")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<FeaturedCollectionService.EntryView> addEntry(
            @PathVariable long id, @Valid @RequestBody EntryRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.addEntry(id, request.toCommand(), user));
    }

    @PutMapping("/{id}/entries/{entryId}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<FeaturedCollectionService.EntryView> updateEntry(
            @PathVariable long id, @PathVariable long entryId,
            @Valid @RequestBody UpdateEntryRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(service.updateEntry(id, entryId, request.toCommand(),
                request.version(), user));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Void> deleteEntry(@PathVariable long id, @PathVariable long entryId,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        service.deleteEntry(id, entryId, user);
        return ApiResponse.ok(null);
    }

    record CollectionRequest(@NotBlank @Size(max = 200) String title,
                             @Size(max = 20_000) String requirementHtml,
                             @NotNull LocalDateTime startsAt,
                             @NotNull LocalDateTime endsAt,
                             boolean assignAll,
                             @Min(1) @Max(50) int entryLimit,
                             List<Long> campusIds,
                             List<Long> userIds) {
        FeaturedCollectionService.CollectionCommand toCommand() {
            return new FeaturedCollectionService.CollectionCommand(title, requirementHtml, startsAt,
                    endsAt, assignAll, entryLimit, campusIds, userIds);
        }
    }

    record UpdateCollectionRequest(@NotBlank @Size(max = 200) String title,
                                   @Size(max = 20_000) String requirementHtml,
                                   @NotNull LocalDateTime startsAt,
                                   @NotNull LocalDateTime endsAt,
                                   boolean assignAll,
                                   @Min(1) @Max(50) int entryLimit,
                                   List<Long> campusIds,
                                   List<Long> userIds,
                                   @Min(1) int version) {
        FeaturedCollectionService.CollectionCommand toCommand() {
            return new FeaturedCollectionService.CollectionCommand(title, requirementHtml, startsAt,
                    endsAt, assignAll, entryLimit, campusIds, userIds);
        }
    }

    record EntryRequest(@NotNull Long photoId,
                        @NotBlank @Size(max = 2_000) String idea,
                        @NotBlank @Size(max = 200) String location) {
        FeaturedCollectionService.EntryCommand toCommand() {
            return new FeaturedCollectionService.EntryCommand(photoId, idea, location);
        }
    }

    record UpdateEntryRequest(@NotNull Long photoId,
                              @NotBlank @Size(max = 2_000) String idea,
                              @NotBlank @Size(max = 200) String location,
                              @Min(1) int version) {
        FeaturedCollectionService.EntryCommand toCommand() {
            return new FeaturedCollectionService.EntryCommand(photoId, idea, location);
        }
    }

    record VersionRequest(@Min(1) int version) {
    }
}

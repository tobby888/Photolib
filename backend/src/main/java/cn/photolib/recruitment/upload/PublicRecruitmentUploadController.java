package cn.photolib.recruitment.upload;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.recruitment.AnonymousRecruitmentRateLimiter;
import cn.photolib.recruitment.RecruitmentTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/public/recruitments/{publicId}/drafts/{draftId}/batches")
@RequiredArgsConstructor
public class PublicRecruitmentUploadController {
    public static final String DRAFT_TOKEN_HEADER = "X-Recruitment-Draft-Token";

    private final RecruitmentUploadService service;
    private final AnonymousRecruitmentRateLimiter limiter;
    private final RecruitmentTaskService taskService;

    @PostMapping
    ApiResponse<RecruitmentUploadService.BatchTicket> create(
            @PathVariable String publicId,
            @PathVariable String draftId,
            @RequestHeader(DRAFT_TOKEN_HEADER) String rawToken,
            @Valid @RequestBody CreateRequest request,
            HttpServletRequest servletRequest) {
        String activePublicId = taskService.requireActivePublicId(publicId);
        limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.UPLOAD_CREATE,
                activePublicId, servletRequest.getRemoteAddr());
        List<RecruitmentUploadService.FileSpec> files = request.files() == null ? null
                : request.files().stream().map(file -> new RecruitmentUploadService.FileSpec(
                        file.fileName(), file.contentType(), file.size(), file.sha256())).toList();
        return ApiResponse.ok(service.create(activePublicId, draftId, rawToken,
                new RecruitmentUploadService.CreateBatch(request.mode(), request.archiveFileName(),
                        request.archiveSize(), files)));
    }

    @PostMapping("/{batchId}/complete")
    ApiResponse<RecruitmentUploadService.BatchView> complete(
            @PathVariable String publicId,
            @PathVariable String draftId,
            @PathVariable String batchId,
            @RequestHeader(DRAFT_TOKEN_HEADER) String rawToken,
            HttpServletRequest servletRequest) {
        String activePublicId = taskService.requireActivePublicId(publicId);
        limiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.UPLOAD_COMPLETE,
                activePublicId, servletRequest.getRemoteAddr());
        return ApiResponse.ok(service.complete(activePublicId, draftId, batchId, rawToken));
    }

    @GetMapping("/{batchId}")
    ApiResponse<RecruitmentUploadService.BatchView> get(
            @PathVariable String publicId,
            @PathVariable String draftId,
            @PathVariable String batchId,
            @RequestHeader(DRAFT_TOKEN_HEADER) String rawToken) {
        return ApiResponse.ok(service.get(publicId, draftId, batchId, rawToken));
    }

    record CreateRequest(@NotNull RecruitmentUploadMode mode,
                         @Size(max = 255) String archiveFileName,
                         @Positive Long archiveSize,
                         @Size(max = 100) List<@Valid FileRequest> files) {
    }

    record FileRequest(@NotBlank @Size(max = 255) String fileName,
                       @NotBlank String contentType,
                       @Positive long size,
                       @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) {
    }
}

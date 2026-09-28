package cn.photolib.recruitment;

import cn.photolib.common.api.ApiResponse;
import cn.photolib.form.FormFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/public/recruitments")
@RequiredArgsConstructor
public class RecruitmentPublicController {
    private final RecruitmentTaskService taskService;
    private final RecruitmentDraftService draftService;
    private final RecruitmentApplicationService applicationService;
    private final AnonymousRecruitmentRateLimiter rateLimiter;

    @GetMapping
    ApiResponse<List<RecruitmentTaskService.PublicTaskView>> active() {
        return ApiResponse.ok(taskService.active());
    }

    @PostMapping("/{publicId}/drafts")
    ApiResponse<RecruitmentDraftService.DraftTicket> createDraft(
            @PathVariable String publicId,
            @Valid @RequestBody CreateDraftRequest request,
            HttpServletRequest servletRequest) {
        String activePublicId = taskService.requireActivePublicId(publicId);
        rateLimiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.DRAFT_CREATE,
                activePublicId, servletRequest.getRemoteAddr());
        return ApiResponse.ok(draftService.create(activePublicId, request.studentId()));
    }

    @PostMapping("/{publicId}/drafts/{draftId}/submit")
    ApiResponse<RecruitmentApplicationService.SubmissionReceipt> submit(
            @PathVariable String publicId,
            @PathVariable String draftId,
            @RequestHeader("X-Recruitment-Draft-Token") String draftToken,
            @Valid @RequestBody SubmitRequest request,
            HttpServletRequest servletRequest) {
        // Submission is the most expensive anonymous operation on this path: full
        // schema validation, an attachment-state query and a write transaction. A
        // valid draft token is required, but that is no reason to leave the only
        // anonymous mutation on the chain without the limiter the others use.
        String activePublicId = taskService.requireActivePublicId(publicId);
        rateLimiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.SUBMIT,
                activePublicId, servletRequest.getRemoteAddr());
        return ApiResponse.ok(applicationService.submit(activePublicId, draftId, draftToken,
                request.studentId(), request.answers()));
    }

    @PostMapping("/{publicId}/drafts/{draftId}/files")
    ApiResponse<FormFileService.UploadTicket> createFileTicket(
            @PathVariable String publicId,
            @PathVariable String draftId,
            @RequestHeader("X-Recruitment-Draft-Token") String draftToken,
            @Valid @RequestBody FileTicketRequest request,
            HttpServletRequest servletRequest) {
        String activePublicId = taskService.requireActivePublicId(publicId);
        rateLimiter.requireAllowed(AnonymousRecruitmentRateLimiter.Action.FILE_UPLOAD_CREATE,
                activePublicId, servletRequest.getRemoteAddr());
        return ApiResponse.ok(applicationService.createFileTicket(activePublicId, draftId, draftToken,
                new FormFileService.TicketRequest(request.fieldId(), request.fileName(),
                        request.contentType(), request.size())));
    }

    record CreateDraftRequest(@NotBlank @Size(max = 128) String studentId) {
    }

    record FileTicketRequest(@NotBlank @Size(max = 64) String fieldId,
                             @NotBlank @Size(max = 255) String fileName,
                             @Size(max = 255) String contentType,
                             @NotNull @Positive Long size) {
    }

    record SubmitRequest(@NotBlank @Size(max = 128) String studentId,
                         @NotNull Map<String, Object> answers) {
    }
}

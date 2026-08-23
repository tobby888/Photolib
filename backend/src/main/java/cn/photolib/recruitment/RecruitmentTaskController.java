package cn.photolib.recruitment;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.recruitment.model.RecruitmentTaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/recruitment-tasks")
@RequiredArgsConstructor
public class RecruitmentTaskController {
    private final RecruitmentTaskService taskService;
    private final RecruitmentApplicationService applicationService;

    @GetMapping
    @PreAuthorize("hasAuthority('RECRUITMENT_VIEW')")
    ApiResponse<PageResponse<RecruitmentTaskService.TaskView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RecruitmentTaskStatus status,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(taskService.list(page, pageSize, keyword, status, user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RECRUITMENT_VIEW')")
    ApiResponse<RecruitmentTaskService.TaskView> get(
            @PathVariable long id,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(taskService.get(id, user));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('RECRUITMENT_PUBLISH')")
    ApiResponse<RecruitmentTaskService.TaskView> create(
            @Valid @RequestBody TaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(taskService.create(request.toCommand(), user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('RECRUITMENT_PUBLISH')")
    ApiResponse<RecruitmentTaskService.TaskView> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateTaskRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(taskService.update(id, request.toCommand(), request.version(), user));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('RECRUITMENT_PUBLISH')")
    ApiResponse<RecruitmentTaskService.TaskView> publish(
            @PathVariable long id,
            @Valid @RequestBody VersionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(taskService.publish(id, request.version(), user));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('RECRUITMENT_PUBLISH')")
    ApiResponse<RecruitmentTaskService.TaskView> close(
            @PathVariable long id,
            @Valid @RequestBody VersionRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(taskService.close(id, request.version(), user));
    }

    @GetMapping("/{id}/applications")
    @PreAuthorize("hasAuthority('RECRUITMENT_VIEW')")
    ApiResponse<PageResponse<RecruitmentApplicationService.ApplicationSummary>> applications(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 64) String studentId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(applicationService.list(id, page, pageSize, studentId, user));
    }

    record TaskRequest(@NotBlank @Size(max = 200) String title,
                       @Size(max = 5_000) String introMarkdown,
                       @NotNull RecruitmentFormSchema formSchema,
                       @NotBlank @Size(max = 100) String studentIdLabel,
                       @Size(max = 500) String studentIdHelp,
                       @NotBlank @Size(max = 100) String uploadLabel,
                       @Size(max = 500) String uploadHelp,
                       boolean uploadRequired,
                       @NotNull LocalDateTime startsAt,
                       @NotNull LocalDateTime endsAt) {
        RecruitmentTaskService.TaskCommand toCommand() {
            return new RecruitmentTaskService.TaskCommand(title, introMarkdown, formSchema,
                    studentIdLabel, studentIdHelp, uploadLabel, uploadHelp, uploadRequired,
                    startsAt, endsAt);
        }
    }

    record UpdateTaskRequest(@NotBlank @Size(max = 200) String title,
                             @Size(max = 5_000) String introMarkdown,
                             @NotNull RecruitmentFormSchema formSchema,
                             @NotBlank @Size(max = 100) String studentIdLabel,
                             @Size(max = 500) String studentIdHelp,
                             @NotBlank @Size(max = 100) String uploadLabel,
                             @Size(max = 500) String uploadHelp,
                             boolean uploadRequired,
                             @NotNull LocalDateTime startsAt,
                             @NotNull LocalDateTime endsAt,
                             @Min(1) int version) {
        RecruitmentTaskService.TaskCommand toCommand() {
            return new RecruitmentTaskService.TaskCommand(title, introMarkdown, formSchema,
                    studentIdLabel, studentIdHelp, uploadLabel, uploadHelp, uploadRequired,
                    startsAt, endsAt);
        }
    }

    record VersionRequest(@Min(1) int version) {
    }
}

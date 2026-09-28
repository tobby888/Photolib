package cn.photolib.survey;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.ApiResponse;
import cn.photolib.common.api.PageResponse;
import cn.photolib.form.FormFileService;
import cn.photolib.recruitment.model.RecruitmentFormSchema;
import cn.photolib.survey.model.SurveyStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 问卷接口。三组接口分别挂三个权限，Service 里再各判一遍（后端是授权边界，
 * 这里的 {@code @PreAuthorize} 让没权限的请求在读参数之前就被拒掉）。
 */
@RestController
@RequestMapping("/surveys")
@RequiredArgsConstructor
public class SurveyController {
    private final SurveyService surveys;
    private final SurveyResponseService responses;

    // ---------------- 发起人（SURVEY_CREATE） ----------------

    @GetMapping("/audience")
    @PreAuthorize("hasAuthority('SURVEY_CREATE')")
    ApiResponse<SurveyService.AudienceOptions> audience(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.audienceOptions(user));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SURVEY_CREATE')")
    ApiResponse<SurveyService.SurveyView> create(@Valid @RequestBody SurveyRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.create(request.toCommand(), user));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SURVEY_CREATE')")
    ApiResponse<SurveyService.SurveyView> update(@PathVariable long id,
                                                 @Valid @RequestBody UpdateSurveyRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.update(id, request.survey().toCommand(), request.version(), user));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('SURVEY_CREATE')")
    ApiResponse<SurveyService.SurveyView> publish(@PathVariable long id, @Valid @RequestBody VersionRequest request,
                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.publish(id, request.version(), user));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('SURVEY_CREATE')")
    ApiResponse<SurveyService.SurveyView> close(@PathVariable long id, @Valid @RequestBody VersionRequest request,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.close(id, request.version(), user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SURVEY_CREATE')")
    ApiResponse<Void> delete(@PathVariable long id, @RequestParam @Min(1) int version,
                             @AuthenticationPrincipal AuthenticatedUser user) {
        surveys.delete(id, version, user);
        return ApiResponse.ok(null);
    }

    // ---------------- 管理列表（SURVEY_CREATE 或 SURVEY_RESULT_VIEW） ----------------

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SURVEY_CREATE','SURVEY_RESULT_VIEW')")
    ApiResponse<PageResponse<SurveyService.SurveyView>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(required = false) SurveyStatus status,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.list(page, pageSize, keyword, status, user));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('SURVEY_CREATE','SURVEY_RESULT_VIEW')")
    ApiResponse<SurveyService.SurveyView> get(@PathVariable long id,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(surveys.get(id, user));
    }

    @GetMapping("/{id}/targets")
    @PreAuthorize("hasAnyAuthority('SURVEY_CREATE','SURVEY_RESULT_VIEW')")
    ApiResponse<List<SurveyResponseService.TargetStatus>> targets(@PathVariable long id,
                                                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.targets(id, user));
    }

    // ---------------- 结果（SURVEY_RESULT_VIEW） ----------------

    @GetMapping("/{id}/responses")
    @PreAuthorize("hasAuthority('SURVEY_RESULT_VIEW')")
    ApiResponse<PageResponse<SurveyResponseService.ResponseSummary>> responses(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.list(id, page, pageSize, keyword, user));
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAuthority('SURVEY_RESULT_VIEW')")
    ApiResponse<SurveyResponseService.Summary> summary(@PathVariable long id,
                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.summary(id, user));
    }

    @GetMapping(value = "/{id}/responses/export", produces = SurveyResponseExport.CONTENT_TYPE)
    @PreAuthorize("hasAuthority('SURVEY_RESULT_VIEW')")
    ResponseEntity<byte[]> export(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        SurveyResponseService.Export export = responses.export(id, user);
        return ResponseEntity.ok()
                // 文件名带中文，必须走 RFC 5987 编码，否则浏览器存成乱码。
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(SurveyResponseExport.CONTENT_TYPE))
                .body(export.content());
    }

    @GetMapping("/responses/{responseId}")
    @PreAuthorize("hasAuthority('SURVEY_RESULT_VIEW')")
    ApiResponse<SurveyResponseService.ResponseDetail> response(@PathVariable long responseId,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.get(responseId, user));
    }

    // ---------------- 填写人（SURVEY_ACCESS） ----------------

    @GetMapping("/assigned")
    @PreAuthorize("hasAuthority('SURVEY_ACCESS')")
    ApiResponse<List<SurveyResponseService.AssignedSurvey>> assigned(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.assigned(user));
    }

    @GetMapping("/{id}/fill")
    @PreAuthorize("hasAuthority('SURVEY_ACCESS')")
    ApiResponse<SurveyResponseService.FillView> fill(@PathVariable long id,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.fillView(id, user));
    }

    @PostMapping("/{id}/files")
    @PreAuthorize("hasAuthority('SURVEY_ACCESS')")
    ApiResponse<FormFileService.UploadTicket> fileTicket(@PathVariable long id,
                                                         @Valid @RequestBody FileTicketRequest request,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.createFileTicket(id, new FormFileService.TicketRequest(
                request.fieldId(), request.fileName(), request.contentType(), request.size()), user));
    }

    @PostMapping("/{id}/responses")
    @PreAuthorize("hasAuthority('SURVEY_ACCESS')")
    ApiResponse<SurveyResponseService.Receipt> submit(@PathVariable long id,
                                                      @Valid @RequestBody SubmitRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(responses.submit(id, request.answers(), user));
    }

    record SurveyRequest(@NotBlank @Size(max = 200) String title,
                         @Size(max = 1_000) String description,
                         @Size(max = 20_000) String introMarkdown,
                         @NotNull RecruitmentFormSchema formSchema,
                         LocalDateTime endsAt,
                         @Size(max = SurveyService.MAX_TARGETS) List<Long> targetUserIds) {
        SurveyService.SurveyCommand toCommand() {
            return new SurveyService.SurveyCommand(title, description, introMarkdown, formSchema, endsAt,
                    targetUserIds);
        }
    }

    /** 更新请求把问卷内容和版本号分开，版本号只在这里出现。 */
    record UpdateSurveyRequest(@NotBlank @Size(max = 200) String title,
                               @Size(max = 1_000) String description,
                               @Size(max = 20_000) String introMarkdown,
                               @NotNull RecruitmentFormSchema formSchema,
                               LocalDateTime endsAt,
                               @Size(max = SurveyService.MAX_TARGETS) List<Long> targetUserIds,
                               @Min(1) int version) {
        SurveyRequest survey() {
            return new SurveyRequest(title, description, introMarkdown, formSchema, endsAt, targetUserIds);
        }
    }

    record VersionRequest(@Min(1) int version) {
    }

    record FileTicketRequest(@NotBlank @Size(max = 64) String fieldId,
                             @NotBlank @Size(max = 255) String fileName,
                             @Size(max = 255) String contentType,
                             @NotNull @Positive Long size) {
    }

    record SubmitRequest(@NotNull Map<String, Object> answers) {
    }
}

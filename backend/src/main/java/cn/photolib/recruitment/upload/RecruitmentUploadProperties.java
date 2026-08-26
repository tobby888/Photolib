package cn.photolib.recruitment.upload;

import cn.photolib.common.upload.SafeImageZipExtractor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload quota for the anonymous recruitment form.
 *
 * <p>These used to be the gallery's constants — 100 images of 100 MiB each, or a
 * 1.5 GB archive expanding to 10 GiB. That budget exists because a department
 * member uploading a shoot legitimately needs it. An applicant attaching a
 * portfolio does not, and the recruitment path hands it out without a login: one
 * client can hold eight open drafts at a time, so the gallery's limits made tens
 * of gigabytes of presigned PUT capacity reachable by anyone with the link.
 *
 * <p>The defaults below are still generous for a portfolio while cutting that
 * exposure by more than an order of magnitude. They are configurable because the
 * right number is a department decision, not a security constant — but the
 * defaults must be safe for a deployment that never sets them.
 *
 * <p>This caps what one accepted request can cost. It does not replace the
 * gateway rate limiting described in {@code README.md}; the in-process limiter
 * fails open behind a reverse proxy by design.
 */
@ConfigurationProperties(prefix = "photolib.recruitment.upload")
public record RecruitmentUploadProperties(
        Integer maxImageCount,
        Long maxImageBytes,
        Long maxArchiveBytes,
        Long maxExpandedBytes,
        Integer maxFileNameLength
) {
    public RecruitmentUploadProperties {
        maxImageCount = positive(maxImageCount, 20);
        maxImageBytes = positive(maxImageBytes, 20L * 1024 * 1024);
        maxArchiveBytes = positive(maxArchiveBytes, 200L * 1024 * 1024);
        maxExpandedBytes = positive(maxExpandedBytes, 400L * 1024 * 1024);
        maxFileNameLength = positive(maxFileNameLength, 255);
    }

    public SafeImageZipExtractor.Limits zipLimits() {
        return new SafeImageZipExtractor.Limits(
                maxImageCount, maxImageBytes, maxExpandedBytes, maxFileNameLength);
    }

    private static Integer positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static Long positive(Long value, long fallback) {
        return value == null || value < 1 ? fallback : value;
    }
}

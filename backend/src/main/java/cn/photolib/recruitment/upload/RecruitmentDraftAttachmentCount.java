package cn.photolib.recruitment.upload;

import lombok.Getter;
import lombok.Setter;

/** One row of {@link RecruitmentUploadItemMapper#countSucceededByDrafts} — a draft and its finalized attachment count. */
@Getter
@Setter
public class RecruitmentDraftAttachmentCount {
    private String draftId;
    private int attachmentCount;
}

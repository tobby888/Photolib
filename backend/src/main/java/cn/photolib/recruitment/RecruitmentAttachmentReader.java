package cn.photolib.recruitment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read-only boundary between immutable applications and the upload pipeline.
 * Implementations must return only successfully finalized objects as attachments.
 */
public interface RecruitmentAttachmentReader {
    DraftAttachmentState stateForDraft(String draftId);

    /**
     * Finalized attachment counts for several drafts at once, keyed by draft id.
     * Drafts without a finalized attachment may be omitted; callers read a missing
     * key as zero. Counts must cover exactly the objects {@link #stateForDraft}
     * would report, so a listing and an export never disagree.
     */
    Map<String, Integer> attachmentCountsByDraft(Collection<String> draftIds);

    record DraftAttachmentState(boolean inProgress, List<Attachment> attachments) {
        public DraftAttachmentState {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    record Attachment(long id, String name, String contentType, long size,
                      String objectKey, String sha256) {
    }
}

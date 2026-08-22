package cn.photolib.recruitment;

import java.util.List;

/**
 * Read-only boundary between immutable applications and the upload pipeline.
 * Implementations must return only successfully finalized objects as attachments.
 */
public interface RecruitmentAttachmentReader {
    DraftAttachmentState stateForDraft(String draftId);

    record DraftAttachmentState(boolean inProgress, List<Attachment> attachments) {
        public DraftAttachmentState {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    record Attachment(long id, String name, String contentType, long size,
                      String objectKey, String sha256) {
    }
}

package cn.photolib.recruitment.upload;

import cn.photolib.recruitment.RecruitmentAttachmentReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DatabaseRecruitmentAttachmentReader implements RecruitmentAttachmentReader {
    private final RecruitmentUploadBatchMapper batchMapper;
    private final RecruitmentUploadItemMapper itemMapper;

    @Override
    public DraftAttachmentState stateForDraft(String draftId) {
        boolean inProgress = batchMapper.countInProgressByDraft(draftId) > 0;
        List<Attachment> attachments = itemMapper.findSucceededByDraft(draftId).stream()
                .map(item -> new Attachment(item.getId(), item.getOriginalFileName(),
                        item.getContentType(), item.getSize(), item.getObjectKey(), item.getSha256()))
                .toList();
        return new DraftAttachmentState(inProgress, attachments);
    }

}

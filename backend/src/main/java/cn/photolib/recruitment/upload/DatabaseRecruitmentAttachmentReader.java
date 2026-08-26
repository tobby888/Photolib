package cn.photolib.recruitment.upload;

import cn.photolib.recruitment.RecruitmentAttachmentReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Override
    public Map<String, Integer> attachmentCountsByDraft(Collection<String> draftIds) {
        List<String> wanted = draftIds == null ? List.of()
                : draftIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        // An empty IN (...) list is a SQL syntax error, so short-circuit before the query.
        if (wanted.isEmpty()) return Map.of();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RecruitmentDraftAttachmentCount row : itemMapper.countSucceededByDrafts(wanted)) {
            counts.put(row.getDraftId(), row.getAttachmentCount());
        }
        return counts;
    }
}

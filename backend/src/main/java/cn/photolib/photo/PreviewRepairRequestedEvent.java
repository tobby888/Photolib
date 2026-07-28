package cn.photolib.photo;

import java.util.List;
import java.util.Objects;

public record PreviewRepairRequestedEvent(List<Long> photoIds, PreviewProfile expectedProfile) {
    public PreviewRepairRequestedEvent {
        photoIds = List.copyOf(Objects.requireNonNull(photoIds, "photoIds"));
        Objects.requireNonNull(expectedProfile, "expectedProfile");
        if (photoIds.isEmpty()) {
            throw new IllegalArgumentException("photoIds 不能为空");
        }
    }
}

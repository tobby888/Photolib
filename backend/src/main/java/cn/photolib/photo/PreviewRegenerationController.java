package cn.photolib.photo;

import cn.photolib.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/preview-generation")
@RequiredArgsConstructor
public class PreviewRegenerationController {
    private final PreviewRegenerationCoordinator coordinator;

    @GetMapping("/status")
    ApiResponse<PreviewRegenerationCoordinator.StatusView> status() {
        return ApiResponse.ok(coordinator.status());
    }
}

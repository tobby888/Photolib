package cn.photolib.common.util;

import java.io.IOException;

public class UploadSizeLimitExceededException extends IOException {
    public UploadSizeLimitExceededException() {
        super("上传内容超过允许大小");
    }
}

package com.testagent.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * vP1: 上传文件大小二次校验，与 Spring multipart 限制叠加生效。
 */
@Component
public class UploadGuard {

    @Value("${app.upload.max-size:20971520}")
    private long maxUploadBytes;

    public void assertSize(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.invalidParam(label + " 文件为空");
        }
        if (file.getSize() > maxUploadBytes) {
            long mb = Math.max(1, maxUploadBytes / 1024 / 1024);
            throw BusinessException.invalidParam(label + " 文件过大，最大 " + mb + "MB");
        }
    }
}

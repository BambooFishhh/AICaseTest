package com.testagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadGuardTest {

    private UploadGuard guard() {
        UploadGuard guard = new UploadGuard();
        ReflectionTestUtils.setField(guard, "maxUploadBytes", 20L * 1024 * 1024);
        return guard;
    }

    @Test
    void rejectsOversizedFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]);
        assertThrows(BusinessException.class, () -> guard().assertSize(file, "PRD PDF"));
    }

    @Test
    void acceptsNormalFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "ok.json", "application/json", "[]".getBytes());
        guard().assertSize(file, "JSON");
    }
}

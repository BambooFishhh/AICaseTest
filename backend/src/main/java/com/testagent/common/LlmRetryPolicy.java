package com.testagent.common;

import org.springframework.web.client.HttpStatusCodeException;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * v6.5: LLM 重试分类。只对超时/网络/429/5xx 等可恢复错误重试，
 * 4xx 与模型拒绝等不可重试错误立即失败，避免无效等待。
 */
public final class LlmRetryPolicy {

    private LlmRetryPolicy() {
    }

    public static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof GenerationCancelledException) {
                return false;
            }
            if (current instanceof HttpStatusCodeException statusException) {
                int code = statusException.getStatusCode().value();
                return code == 408 || code == 409 || code == 425 || code == 429 || code >= 500;
            }
            if (current instanceof SocketTimeoutException || current instanceof IOException) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (isClientErrorPattern(message)) {
                return false;
            }
            if (message.contains("read timed out")
                    || message.contains("connect timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("timeout")
                    || message.contains("timed out")
                    || message.contains("429")
                    || message.contains("500")
                    || message.contains("502")
                    || message.contains("503")
                    || message.contains("504")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isClientErrorPattern(String message) {
        return message.matches("(?s).*\\[\\s*4\\d{2}\\s*\\].*")
                || message.matches("(?s).*http\\s+4\\d{2}.*")
                || message.matches("(?s).*status\\s+4\\d{2}.*");
    }
}

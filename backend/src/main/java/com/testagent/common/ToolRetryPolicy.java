package com.testagent.common;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * v6.6: 工具调用重试策略。只对幂等工具的重试，且只重试超时/网络/进程不可用错误，
 * 点击/输入/提交等有副作用的工具不做自动重试。
 */
public final class ToolRetryPolicy {

    private ToolRetryPolicy() {
    }

    public static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof IOException) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("timeout")
                    || message.contains("timed out")
                    || message.contains("stdout 已关闭")
                    || message.contains("未启动")
                    || message.contains("已停止")
                    || message.contains("connection reset")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isIdempotentTool(String serverName, String toolName) {
        String tool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        return tool.contains("screenshot")
                || tool.contains("status")
                || tool.contains("scroll")
                || tool.contains("get_")
                || tool.contains("read")
                || tool.contains("list")
                || tool.contains("search")
                || tool.contains("analyze")
                || tool.contains("extract")
                || tool.contains("review");
    }
}

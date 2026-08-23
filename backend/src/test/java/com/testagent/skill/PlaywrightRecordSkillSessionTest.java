package com.testagent.skill;

import com.testagent.mcp.McpClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.11(E12): Playwright 多会话隔离单测。
 * 背景：MCP Server 原先只持有一个全局 browser/context/page，并发执行任务
 * 共享同一浏览器实例互相干扰（导航抢页、录屏互串、取消全局杀浏览器）。
 * 修复后所有工具调用都携带 session_id，Skill 侧负责透传。
 */
class PlaywrightRecordSkillSessionTest {

    private McpClientManager mcpClientManager;
    private PlaywrightRecordSkill skill;

    @BeforeEach
    void setUp() {
        mcpClientManager = mock(McpClientManager.class);
        skill = new PlaywrightRecordSkill();
        ReflectionTestUtils.setField(skill, "mcpClientManager", mcpClientManager);
        ReflectionTestUtils.setField(skill, "outputDir", "outputs");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedArgs(String toolName) throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mcpClientManager).callTool(eq("playwright"), eq(toolName), captor.capture());
        return captor.getValue();
    }

    @Test
    void browserLaunchPassesSessionIdAndReturnsIt() throws Exception {
        String returned = skill.browserLaunch("exec-abc123", true, 1280, 800);

        assertEquals("exec-abc123", returned);
        Map<String, Object> args = capturedArgs("browser_launch");
        assertEquals("exec-abc123", args.get("session_id"));
        assertEquals(true, args.get("headless"));
    }

    @Test
    void browserNavigatePassesSessionId() throws Exception {
        skill.browserNavigate("exec-abc123", "https://example.com");

        Map<String, Object> args = capturedArgs("browser_navigate");
        assertEquals("exec-abc123", args.get("session_id"));
        assertEquals("https://example.com", args.get("url"));
    }

    @Test
    void takeScreenshotFilenameContainsSessionPrefix() throws Exception {
        skill.takeScreenshot("exec-abc123");

        Map<String, Object> args = capturedArgs("browser_take_screenshot");
        assertEquals("exec-abc123", args.get("session_id"));
        // v7.11(E12): 截图文件名带会话前缀，防止并发任务毫秒级时间戳碰撞互相覆盖
        assertTrue(String.valueOf(args.get("path")).contains("exec-abc123"),
                "截图路径应包含会话前缀: " + args.get("path"));
    }

    @Test
    void stopRecordingPassesSessionId() throws Exception {
        skill.stopRecording("exec-abc123", "outputs/recordings/exec-abc123/video.webm");

        Map<String, Object> args = capturedArgs("browser_video_save");
        assertEquals("exec-abc123", args.get("session_id"));
    }

    @Test
    void closeSessionOnlyTargetsGivenSessionAndSwallowsErrors() throws Exception {
        // 正常关闭：只携带本会话 ID（并发任务的其他会话不受影响）
        skill.closeSession("exec-abc123");
        Map<String, Object> args = capturedArgs("browser_close");
        assertEquals("exec-abc123", args.get("session_id"));

        // MCP 异常时不向上抛（收尾路径不能因关浏览器失败而中断）
        when(mcpClientManager.callTool(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("connection closed"));
        assertDoesNotThrow(() -> skill.closeSession("exec-other"));
    }

    @Test
    void getPageStatusParsesSessionScopedResponse() throws Exception {
        when(mcpClientManager.callTool(eq("playwright"), eq("browser_get_page_status"), any()))
                .thenReturn("{\"url\":\"https://example.com/home\",\"title\":\"首页\"}");

        Map<String, String> status = skill.getPageStatus("exec-abc123");

        assertEquals("https://example.com/home", status.get("url"));
        Map<String, Object> args = capturedArgs("browser_get_page_status");
        assertEquals("exec-abc123", args.get("session_id"));
    }
}

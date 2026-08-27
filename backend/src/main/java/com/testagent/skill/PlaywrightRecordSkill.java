package com.testagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.testagent.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * v2.7: 基于 Playwright MCP 的浏览器操作 Skill。
 * 方法签名与原 BrowserSkill 对齐，v2.8 切换执行链路，v2.9 移除 Selenium。
 *
 * 特点：
 * - 通过 MCP 协议调用 playwright-mcp-server
 * - 真正的视频录屏（Playwright recordVideo，WebM 格式）
 * - 截图标注复用 Graphics2D（红圈+十字准星+坐标文本）
 *
 * v2.9 起 BrowserSkill(Selenium) 已移除，本类为唯一浏览器操作实现。
 */
@Component
public class PlaywrightRecordSkill {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightRecordSkill.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private McpClientManager mcpClientManager;

    @Value("${app.output-dir:outputs}")
    private String outputDir;

    // v8.9.7(临时): 移动设备模拟预设名（如 iPhone 14）。默认为空=桌面宽高模式；
    // 仅显式配置时才透传给 Playwright MCP 做设备模拟，便于后续切回 B 端。
    @Value("${app.execution.browser-device:}")
    private String browserDevice;

    /**
     * 启动浏览器会话。
     * v7.11(E12): sessionId（通常为 executionId）传入 MCP Server 做多会话隔离，
     * 并发执行的各任务各自持有独立浏览器实例，互不干扰。
     * @param sessionId 会话标识（executionId）
     * @param headless 是否无头模式
     * @param width    窗口宽度
     * @param height   窗口高度
     * @return 会话 ID
     */
    public String browserLaunch(String sessionId, boolean headless, int width, int height) {
        String videoDir = resolveOutputPath("recordings");
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("session_id", sessionId);
            params.put("headless", headless);
            params.put("width", width);
            params.put("height", height);
            params.put("video_dir", videoDir);
            if (browserDevice != null && !browserDevice.isBlank()) {
                params.put("device", browserDevice.trim());
            }
            mcpClientManager.callTool("playwright", "browser_launch", params);
            log.info("Playwright 浏览器已启动: session={}, headless={}, size={}x{}, device={}, videoDir={}",
                    sessionId, headless, width, height,
                    browserDevice == null || browserDevice.isBlank() ? "desktop" : browserDevice, videoDir);
        } catch (Exception e) {
            log.error("Playwright 浏览器启动失败: {}", e.getMessage());
            throw new RuntimeException("Playwright 启动失败", e);
        }
        return sessionId;
    }

    // v8.9.7(临时): 向浏览器会话注入 localStorage 键值（token 型前端登录态，cookie 注入不适用）
    public void setStorage(String sessionId, Map<String, Object> storage) {
        try {
            if (storage == null || storage.isEmpty()) {
                return;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            storage.forEach((k, v) -> {
                Map<String, Object> it = new HashMap<>();
                it.put("key", k);
                it.put("value", v == null ? "" : String.valueOf(v));
                items.add(it);
            });
            mcpClientManager.callTool("playwright", "browser_set_storage",
                    Map.of("session_id", sessionId, "storage", items));
            log.info("Playwright 注入 localStorage: session={}, keys={}", sessionId, items.size());
        } catch (Exception e) {
            log.warn("注入 localStorage 失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 导航到指定 URL。
     */
    public void browserNavigate(String sessionId, String url) {
        try {
            mcpClientManager.callTool("playwright", "browser_navigate",
                    Map.of("session_id", sessionId, "url", url));
            log.info("导航完成: session={}, url={}", sessionId, url);
        } catch (Exception e) {
            log.error("导航失败: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("导航失败", e);
        }
    }

    /**
     * 截图并保存到本地文件。
     * v7.11(E12): 文件名带会话前缀，防止并发执行毫秒级时间戳碰撞互相覆盖。
     * @return 截图文件路径
     */
    public String takeScreenshot(String sessionId) {
        String screenshotPath = resolveOutputPath(
                "screenshots/" + sessionId + "-" + System.currentTimeMillis() + ".png");
        try {
            mcpClientManager.callTool("playwright", "browser_take_screenshot",
                    Map.of("session_id", sessionId, "path", screenshotPath));
            log.info("截图已保存: path={}", screenshotPath);
            return screenshotPath;
        } catch (Exception e) {
            log.error("截图失败: {}", e.getMessage());
            throw new RuntimeException("截图失败", e);
        }
    }

    /**
     * 截图并标注点击位置。
     * @param clickX 点击 X 坐标（0 表示不标注）
     * @param clickY 点击 Y 坐标（0 表示不标注）
     * @return 截图文件路径
     */
    public String takeScreenshotWithMarker(String sessionId, int clickX, int clickY) {
        String screenshotPath = takeScreenshot(sessionId);
        if (clickX > 0 && clickY > 0) {
            try {
                annotateScreenshot(screenshotPath, clickX, clickY);
            } catch (Exception e) {
                log.warn("截图标注失败: {}", e.getMessage());
            }
        }
        return screenshotPath;
    }

    /**
     * 基于坐标的视觉点击。
     */
    public void visualClick(String sessionId, int x, int y) {
        try {
            mcpClientManager.callTool("playwright", "browser_visual_click",
                    Map.of("session_id", sessionId, "x", x, "y", y));
            log.info("视觉点击完成: ({}, {})", x, y);
        } catch (Exception e) {
            log.error("视觉点击失败: ({}, {}), error={}", x, y, e.getMessage());
            throw new RuntimeException("视觉点击失败", e);
        }
    }

    /**
     * 基于 CSS 选择器的元素点击。
     * @param selectorType   选择器类型（兼容 BrowserSkill，内部统一转 CSS）
     * @param selectorValue  选择器值
     */
    public int[] domClick(String sessionId, String selectorType, String selectorValue) {
        String cssSelector = buildCssSelector(selectorType, selectorValue);
        try {
            String response = mcpClientManager.callTool("playwright", "browser_dom_click",
                    Map.of("session_id", sessionId, "selector", cssSelector));
            int[] position = parseClickPosition(response);
            log.info("DOM 点击完成: {}={}, position={}", selectorType, selectorValue,
                    position == null ? "unknown" : position[0] + "," + position[1]);
            return position;
        } catch (Exception e) {
            log.error("DOM 点击失败: {}={}, error={}", selectorType, selectorValue, e.getMessage());
            throw new RuntimeException("DOM 点击失败", e);
        }
    }

    /** 输入框填充 */
    public int[] fillInput(String sessionId, String selectorType, String selectorValue, String value) {
        String cssSelector = buildCssSelector(selectorType, selectorValue);
        try {
            String response = mcpClientManager.callTool("playwright", "browser_fill",
                    Map.of("session_id", sessionId, "selector", cssSelector,
                            "value", value == null ? "" : value));
            int[] position = parseClickPosition(response);
            log.info("输入完成: {}={}, position={}", selectorType, selectorValue,
                    position == null ? "unknown" : position[0] + "," + position[1]);
            return position;
        } catch (Exception e) {
            log.error("输入失败: {}={}, error={}", selectorType, selectorValue, e.getMessage());
            throw new RuntimeException("输入失败", e);
        }
    }

    /** 发送键盘按键（如 Enter，用于提交搜索等操作） */
    public void pressKey(String sessionId, String key) {
        try {
            mcpClientManager.callTool("playwright", "browser_key_press",
                    Map.of("session_id", sessionId, "key", key == null ? "Enter" : key));
            log.info("按键完成: {}", key);
        } catch (Exception e) {
            log.error("按键失败: key={}, error={}", key, e.getMessage());
            throw new RuntimeException("按键失败", e);
        }
    }

    /** 上下滚动页面（找不到元素时由 Agent 兜底使用） */
    public void scroll(String sessionId, String direction, Integer amount) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("session_id", sessionId);
            args.put("direction", direction == null ? "down" : direction);
            if (amount != null && amount > 0) {
                args.put("amount", amount);
            }
            mcpClientManager.callTool("playwright", "browser_scroll", args);
            log.info("页面滚动完成: direction={}, amount={}", direction, amount);
        } catch (Exception e) {
            log.error("页面滚动失败: direction={}, error={}", direction, e.getMessage());
            throw new RuntimeException("页面滚动失败", e);
        }
    }

    /** 注入登录 Cookie，跳过登录界面 */
    public void addCookies(String sessionId, List<Map<String, Object>> cookies) {
        try {
            mcpClientManager.callTool("playwright", "browser_add_cookies",
                    Map.of("session_id", sessionId, "cookies", cookies == null ? List.of() : cookies));
            log.info("注入 Cookie 数量: {}", cookies == null ? 0 : cookies.size());
        } catch (Exception e) {
            log.error("注入 Cookie 失败: {}", e.getMessage());
            throw new RuntimeException("注入 Cookie 失败", e);
        }
    }

    /**
     * 获取当前页面状态信息。
     * @return 包含 url、title、textSnippet 的 Map
     */
    public Map<String, String> getPageStatus(String sessionId) {
        try {
            String response = mcpClientManager.callTool("playwright", "browser_get_page_status",
                    Map.of("session_id", sessionId));
            @SuppressWarnings("unchecked")
            Map<String, String> status = objectMapper.readValue(response, Map.class);
            log.info("获取页面状态: url={}", status.get("url"));
            return status;
        } catch (Exception e) {
            log.error("获取页面状态失败: {}", e.getMessage());
            throw new RuntimeException("获取页面状态失败", e);
        }
    }

    /**
     * 开始录屏。Playwright 在 browserLaunch 时已通过 recordVideo 启动录屏，此方法为空操作。
     */
    public void startRecording(String sessionId, String outputDir) {
        log.info("Playwright 录屏已在启动时自动开启");
    }

    /**
     * 停止录屏并保存视频文件。
     * v7.11(E12): 按会话保存——只终结指定会话的浏览器，并发任务互不影响。
     * @param sessionId 会话标识（executionId）
     * @param filename 视频保存路径（.webm）
     * @return 视频文件路径
     */
    public String stopRecording(String sessionId, String filename) {
        try {
            String savePath = resolveOutputPath(filename);
            String videoPath = mcpClientManager.callTool("playwright", "browser_video_save",
                    Map.of("session_id", sessionId, "filename", savePath));
            log.info("录屏视频已保存: session={}, path={}", sessionId, savePath);
            return videoPath;
        } catch (Exception e) {
            log.error("保存录屏失败: session={}, error={}", sessionId, e.getMessage());
            throw new RuntimeException("保存录屏失败", e);
        }
    }

    private String resolveOutputPath(String relativePath) {
        Path baseDir = Paths.get(outputDir).toAbsolutePath().normalize();
        String normalized = relativePath.replace('\\', '/');
        String prefix = Paths.get(outputDir).toString().replace('\\', '/');
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        if (normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length());
        }
        return baseDir.resolve(normalized).normalize().toString();
    }

    private int[] parseClickPosition(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.isObject() && node.hasNonNull("x") && node.hasNonNull("y")) {
                return new int[]{node.path("x").asInt(0), node.path("y").asInt(0)};
            }
        } catch (Exception e) {
            log.debug("点击坐标解析失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 关闭浏览器。
     * v7.11(E12): 只关闭指定会话——取消/收尾某任务不再全局杀浏览器，并发任务互不影响。
     */
    public void closeSession(String sessionId) {
        try {
            mcpClientManager.callTool("playwright", "browser_close",
                    Map.of("session_id", sessionId));
            log.info("Playwright 浏览器已关闭: session={}", sessionId);
        } catch (Exception e) {
            log.error("关闭浏览器失败: session={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 在截图上绘制红色圆圈+十字准星标注点击位置。
     * 复用 BrowserSkill 的标注逻辑。
     */
    private void annotateScreenshot(String imagePath, int x, int y) throws Exception {
        BufferedImage image = ImageIO.read(new File(imagePath));
        Graphics2D g = image.createGraphics();

        // 红色圆圈（半径 20px，线宽 3px）
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(3));
        g.drawOval(x - 20, y - 20, 40, 40);

        // 十字准星（横线 + 竖线，各 30px）
        g.drawLine(x - 30, y, x + 30, y);
        g.drawLine(x, y - 30, x, y + 30);

        // 坐标文本
        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.drawString("click: (" + x + ", " + y + ")", x + 25, y - 25);

        g.dispose();
        ImageIO.write(image, "png", new File(imagePath));
    }

    /**
     * 将 BrowserSkill 的选择器类型转换为 CSS 选择器。
     */
    private String buildCssSelector(String selectorType, String selectorValue) {
        switch (selectorType) {
            case "id":
                return "#" + selectorValue;
            case "css":
                return selectorValue;
            case "data-testid":
                return "[data-testid='" + selectorValue + "']";
            case "class":
                return "." + selectorValue;
            case "ref":
                return "[ref='" + selectorValue + "']";
            case "aria-label":
                return "[aria-label='" + selectorValue + "']";
            case "xpath":
                // Playwright 支持 xpath= 前缀
                return "xpath=" + selectorValue;
            default:
                return selectorValue;
        }
    }
}

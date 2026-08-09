package com.testagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * v2.7: 基于 Playwright MCP 的浏览器操作 Skill。
 * 方法签名与 BrowserSkill 对齐，为 v2.8 执行链路切换做准备。
 *
 * 特点：
 * - 通过 MCP 协议调用 playwright-mcp-server
 * - 真正的视频录屏（Playwright recordVideo，WebM 格式）
 * - 截图标注复用 Graphics2D（红圈+十字准星+坐标文本）
 *
 * 过渡期与 BrowserSkill 共存，v2.8 切换执行链路，v2.9 清理 Selenium。
 */
@Component
public class PlaywrightRecordSkill {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightRecordSkill.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private McpClientManager mcpClientManager;

    @Value("${app.output-dir:outputs}")
    private String outputDir;

    /**
     * 启动浏览器会话。
     * @param headless 是否无头模式
     * @param width    窗口宽度
     * @param height   窗口高度
     * @return 会话 ID（固定值，Playwright 单浏览器模型）
     */
    public String browserLaunch(boolean headless, int width, int height) {
        String videoDir = Paths.get(outputDir, "recordings").toString();
        try {
            mcpClientManager.callTool("playwright", "browser_launch", Map.of(
                    "headless", headless,
                    "width", width,
                    "height", height,
                    "video_dir", videoDir
            ));
            log.info("Playwright 浏览器已启动: headless={}, size={}x{}, videoDir={}", headless, width, height, videoDir);
        } catch (Exception e) {
            log.error("Playwright 浏览器启动失败: {}", e.getMessage());
            throw new RuntimeException("Playwright 启动失败", e);
        }
        return "playwright-session";
    }

    /**
     * 导航到指定 URL。
     */
    public void browserNavigate(String sessionId, String url) {
        try {
            mcpClientManager.callTool("playwright", "browser_navigate", Map.of("url", url));
            log.info("导航完成: url={}", url);
        } catch (Exception e) {
            log.error("导航失败: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("导航失败", e);
        }
    }

    /**
     * 截图并保存到本地文件。
     * @return 截图文件路径
     */
    public String takeScreenshot(String sessionId) {
        String screenshotPath = Paths.get(outputDir, "screenshots",
                System.currentTimeMillis() + ".png").toString();
        try {
            mcpClientManager.callTool("playwright", "browser_take_screenshot",
                    Map.of("path", screenshotPath));
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
                    Map.of("x", x, "y", y));
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
    public void domClick(String sessionId, String selectorType, String selectorValue) {
        String cssSelector = buildCssSelector(selectorType, selectorValue);
        try {
            mcpClientManager.callTool("playwright", "browser_dom_click",
                    Map.of("selector", cssSelector));
            log.info("DOM 点击完成: {}={}", selectorType, selectorValue);
        } catch (Exception e) {
            log.error("DOM 点击失败: {}={}, error={}", selectorType, selectorValue, e.getMessage());
            throw new RuntimeException("DOM 点击失败", e);
        }
    }

    /**
     * 获取当前页面状态信息。
     * @return 包含 url、title、textSnippet 的 Map
     */
    public Map<String, String> getPageStatus(String sessionId) {
        try {
            String response = mcpClientManager.callTool("playwright", "browser_get_page_status", new HashMap<>());
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
     * @param filename 视频保存路径（.webm）
     * @return 视频文件路径
     */
    public String stopRecording(String filename) {
        try {
            String videoPath = mcpClientManager.callTool("playwright", "browser_video_save",
                    Map.of("filename", filename));
            log.info("录屏视频已保存: path={}", videoPath);
            return videoPath;
        } catch (Exception e) {
            log.error("保存录屏失败: {}", e.getMessage());
            throw new RuntimeException("保存录屏失败", e);
        }
    }

    /**
     * 关闭浏览器。
     */
    public void closeSession(String sessionId) {
        try {
            mcpClientManager.callTool("playwright", "browser_close", new HashMap<>());
            log.info("Playwright 浏览器已关闭");
        } catch (Exception e) {
            log.error("关闭浏览器失败: {}", e.getMessage());
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

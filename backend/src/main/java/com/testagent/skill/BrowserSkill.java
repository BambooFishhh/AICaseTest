package com.testagent.skill;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * v2.0: 浏览器操作 Skill
 * 基于 Selenium WebDriver 实现浏览器启动、导航、截图、点击等操作。
 * 通过 ConcurrentHashMap 管理多个浏览器会话。
 */
@Component
public class BrowserSkill {

    private static final Logger log = LoggerFactory.getLogger(BrowserSkill.class);

    /** 会话表：sessionId → WebDriver 实例 */
    private final Map<String, WebDriver> sessions = new ConcurrentHashMap<>();

    /** 截图输出根目录（项目根目录下） */
    private static final String SCREENSHOT_DIR = "outputs/screenshots";

    /** v2.4: 录屏调度器 */
    private ScheduledExecutorService recordingScheduler;
    /** v2.4: 当前录屏帧文件路径列表 */
    private final List<String> currentRecordingFrames = new CopyOnWriteArrayList<>();
    /** v2.4: 录屏运行标志 */
    private volatile boolean recording = false;

    /**
     * 启动浏览器会话。
     * @param headless 是否使用无头模式
     * @param width    浏览器窗口宽度
     * @param height   浏览器窗口高度
     * @return 会话 ID（UUID 前 8 位）
     */
    public String browserLaunch(boolean headless, int width, int height) {
        // 自动管理 ChromeDriver
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        // 配置无头模式
        if (headless) {
            options.addArguments("--headless=new");
        }
        // 配置窗口大小
        options.addArguments("--window-size=" + width + "," + height);
        // 无沙箱模式，提升 CI 环境兼容性
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        WebDriver driver = new ChromeDriver(options);
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        sessions.put(sessionId, driver);
        log.info("浏览器会话已启动: sessionId={}, headless={}, size={}x{}", sessionId, headless, width, height);
        return sessionId;
    }

    /**
     * 导航到指定 URL，并等待页面加载完成。
     * @param sessionId 会话 ID
     * @param url       目标 URL
     */
    public void browserNavigate(String sessionId, String url) {
        WebDriver driver = getDriver(sessionId);
        driver.get(url);
        // 等待 document.readyState == "complete"，超时 15s
        new WebDriverWait(driver, Duration.ofSeconds(15)).until(
                webDriver -> ((JavascriptExecutor) webDriver)
                        .executeScript("return document.readyState").equals("complete"));
        log.info("导航完成: sessionId={}, url={}", sessionId, url);
    }

    /**
     * 对当前页面截图并保存到本地文件。
     * @param sessionId 会话 ID
     * @return 截图文件路径
     */
    public String takeScreenshot(String sessionId) {
        WebDriver driver = getDriver(sessionId);
        File srcFile = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        // 自动创建目录
        Path dirPath = Paths.get(SCREENSHOT_DIR, sessionId);
        try {
            Files.createDirectories(dirPath);
            String fileName = System.currentTimeMillis() + ".png";
            Path targetPath = dirPath.resolve(fileName);
            Files.copy(srcFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("截图已保存: sessionId={}, path={}", sessionId, targetPath);
            return targetPath.toString();
        } catch (IOException e) {
            log.error("保存截图失败: sessionId={}", sessionId, e);
            throw new RuntimeException("保存截图失败", e);
        }
    }

    /**
     * 基于坐标的视觉点击。
     * @param sessionId 会话 ID
     * @param x         横坐标
     * @param y         纵坐标
     */
    public void visualClick(String sessionId, int x, int y) {
        WebDriver driver = getDriver(sessionId);
        // 通过 Actions 按坐标偏移点击
        new Actions(driver).moveByOffset(x, y).click().perform();
        log.info("视觉点击完成: sessionId=({}, {})", x, y);
    }

    /**
     * 基于 DOM 选择器的元素点击。
     * @param sessionId      会话 ID
     * @param selectorType   选择器类型：id/css/xpath/data-testid/class/ref/aria-label
     * @param selectorValue  选择器值
     */
    public void domClick(String sessionId, String selectorType, String selectorValue) {
        WebDriver driver = getDriver(sessionId);
        By locator = buildBy(selectorType, selectorValue);
        // 等待元素可点击，超时 10s
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        WebElement element = wait.until(ExpectedConditions.elementToBeClickable(locator));
        element.click();
        log.info("DOM 点击完成: sessionId={}, {}={}", sessionId, selectorType, selectorValue);
    }

    /**
     * 获取当前页面状态信息。
     * @param sessionId 会话 ID
     * @return 包含 url、title、textSnippet 的 Map
     */
    public Map<String, String> getPageStatus(String sessionId) {
        WebDriver driver = getDriver(sessionId);
        Map<String, String> status = new HashMap<>();
        status.put("url", driver.getCurrentUrl());
        status.put("title", driver.getTitle());
        // body 文本前 500 字符
        String bodyText = driver.findElement(By.tagName("body")).getText();
        String snippet = bodyText.length() > 500 ? bodyText.substring(0, 500) : bodyText;
        status.put("textSnippet", snippet);
        log.info("获取页面状态: sessionId={}, url={}", sessionId, status.get("url"));
        return status;
    }

    /**
     * 关闭并移除会话。
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        WebDriver driver = sessions.remove(sessionId);
        if (driver == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        driver.quit();
        log.info("会话已关闭: sessionId={}", sessionId);
    }

    /**
     * 根据 sessionId 获取 WebDriver，不存在则抛出 IllegalArgumentException。
     */
    private WebDriver getDriver(String sessionId) {
        WebDriver driver = sessions.get(sessionId);
        if (driver == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return driver;
    }

    /**
     * 根据选择器类型构建 By 定位器。
     */
    private By buildBy(String selectorType, String selectorValue) {
        switch (selectorType) {
            case "id":
                return By.id(selectorValue);
            case "css":
                return By.cssSelector(selectorValue);
            case "xpath":
                return By.xpath(selectorValue);
            case "data-testid":
                return By.cssSelector("[data-testid='" + selectorValue + "']");
            case "class":
                return By.className(selectorValue);
            case "ref":
                return By.cssSelector("[ref='" + selectorValue + "']");
            case "aria-label":
                return By.cssSelector("[aria-label='" + selectorValue + "']");
            default:
                throw new IllegalArgumentException("不支持的选择器类型: " + selectorType);
        }
    }

    /**
     * v2.4: 开始录屏。每 2 秒抓取一帧截图，最多 60 帧（约 2 分钟）。
     * @param sessionId 浏览器会话 ID
     * @param outputDir 帧文件输出目录
     */
    public void startRecording(String sessionId, String outputDir) {
        currentRecordingFrames.clear();
        recording = true;
        recordingScheduler = Executors.newSingleThreadScheduledExecutor();
        int[] frameNum = {0};
        recordingScheduler.scheduleAtFixedRate(() -> {
            if (!recording || frameNum[0] >= 60) {
                recordingScheduler.shutdown();
                return;
            }
            try {
                WebDriver driver = sessions.get(sessionId);
                if (driver != null) {
                    String filename = String.format("frame_%03d.png", frameNum[0]++);
                    Path path = Paths.get(outputDir, filename);
                    File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    Files.copy(screenshot.toPath(), path);
                    currentRecordingFrames.add(path.toString().replace("\\", "/"));
                }
            } catch (Exception e) { /* 忽略单帧失败 */ }
        }, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * v2.4: 停止录屏并返回所有帧文件路径列表。
     * @return 帧文件路径列表
     */
    public List<String> stopRecording() {
        recording = false;
        if (recordingScheduler != null) {
            recordingScheduler.shutdown();
            try { recordingScheduler.awaitTermination(3, TimeUnit.SECONDS); } catch (Exception e) {}
        }
        return new ArrayList<>(currentRecordingFrames);
    }
}

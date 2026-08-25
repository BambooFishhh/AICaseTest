package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.DirItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * v3.1: 文件系统目录列表 API，供前端目录选择器懒加载调用。
 * v8.4fix: 可浏览范围收敛到 app.filesystem.browse-roots 白名单（默认项目目录+克隆目录），
 * 禁止任意登录用户枚举服务器全盘目录。
 */
@RestController
@RequestMapping("/api/filesystem")
public class FilesystemController {

    @Value("${app.filesystem.browse-roots:${app.projects-dir:projects},${app.git.clone-dir:data/git-repos}}")
    private String browseRoots;

    @GetMapping("/dirs")
    public ApiResponse<List<DirItem>> listDirs(@RequestParam(required = false) String path) {
        List<DirItem> items = new ArrayList<>();

        if (path == null || path.isBlank()) {
            // v8.4fix: 根节点只返回配置的白名单目录，不再暴露系统盘符
            for (String root : browseRoots.split(",")) {
                String trimmed = root.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                File rootDir = new File(trimmed).getAbsoluteFile();
                if (rootDir.isDirectory()) {
                    items.add(new DirItem(rootDir.getName(), rootDir.getAbsolutePath(), false));
                }
            }
            return ApiResponse.success(items);
        }

        // 安全：规范化后必须落在白名单根目录内（normalize 后已无 ..，旧 contains("..") 检查实际无效）
        Path normalized = Paths.get(path).toAbsolutePath().normalize();
        if (!isWithinBrowseRoots(normalized)) {
            throw new IllegalArgumentException("非法路径");
        }

        File dir = normalized.toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] children = dir.listFiles(File::isDirectory);
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File child : children) {
                    // 跳过隐藏目录和系统目录（如 $RECYCLE.BIN, System Volume Information）
                    if (!child.isHidden() && !child.getName().startsWith("$")) {
                        items.add(new DirItem(child.getName(), child.getAbsolutePath(), false));
                    }
                }
            }
        }

        return ApiResponse.success(items);
    }

    private boolean isWithinBrowseRoots(Path target) {
        for (String root : browseRoots.split(",")) {
            String trimmed = root.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path rootPath = Paths.get(trimmed).toAbsolutePath().normalize();
            if (target.startsWith(rootPath)) {
                return true;
            }
        }
        return false;
    }
}

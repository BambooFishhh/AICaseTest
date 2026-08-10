package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.DirItem;
import org.springframework.web.bind.annotation.CrossOrigin;
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
 * v3.1: 文件系统目录列表 API，供前端目录选择器懒加载调用
 */
@RestController
@RequestMapping("/api/filesystem")
@CrossOrigin
public class FilesystemController {

    @GetMapping("/dirs")
    public ApiResponse<List<DirItem>> listDirs(@RequestParam(required = false) String path) {
        List<DirItem> items = new ArrayList<>();

        if (path == null || path.isBlank()) {
            // 返回系统根盘符（Windows: C:\, D:\ 等；Linux: /）
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    String rootPath = root.getPath();
                    // 盘符名：C:\ → 本地磁盘 (C:)
                    String name = rootPath.length() >= 2 && rootPath.charAt(1) == ':'
                            ? "本地磁盘 (" + rootPath.charAt(0) + ":)"
                            : rootPath;
                    items.add(new DirItem(name, rootPath, false));
                }
            }
        } else {
            // 安全：规范化路径，拒绝路径遍历攻击
            Path normalized = Paths.get(path).normalize();
            String normalizedStr = normalized.toString();
            if (normalizedStr.contains("..")) {
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
        }

        return ApiResponse.success(items);
    }
}

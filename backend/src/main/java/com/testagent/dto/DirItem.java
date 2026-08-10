package com.testagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * v3.1: 目录项 DTO，用于前端目录选择器懒加载
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DirItem {
    /** 目录名 */
    private String name;
    /** 完整路径 */
    private String path;
    /** 是否叶子节点（暂不预判，前端懒加载） */
    private boolean leaf;
}

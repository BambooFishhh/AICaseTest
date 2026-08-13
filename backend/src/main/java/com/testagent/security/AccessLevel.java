package com.testagent.security;

/**
 * v4.3: 项目访问级别。
 */
public enum AccessLevel {
    NONE,      // 无权限（列表不可见）
    VIEWER,    // 只读（含复制执行）
    OPERATOR,  // 增删改查 + 执行
    OWNER      // 项目创建者 / 组创建者 / 系统管理员
}

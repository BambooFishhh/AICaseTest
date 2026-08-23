package com.testagent.service;

import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.11(T1/T2): 用例 ID 全局唯一分配器单测。
 * 背景：test_cases.id 是全局主键，历史按项目独立编号（项目内 max+1 / TC-001 重编 /
 * size()+1）导致跨项目同号用例经 JPA merge 静默互相覆盖。
 */
class TestCaseIdAllocatorTest {

    private TestCaseRepository repository;
    private TestCaseIdAllocator allocator;

    @BeforeEach
    void setUp() {
        repository = mock(TestCaseRepository.class);
        allocator = new TestCaseIdAllocator();
        ReflectionTestUtils.setField(allocator, "testCaseRepository", repository);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TestCase tc(String id, String projectId) {
        TestCase t = new TestCase();
        t.setId(id);
        t.setProjectId(projectId);
        return t;
    }

    @Test
    void coldStartLoadsGlobalMaxAcrossProjects() {
        // 项目 A 已有 TC-005，项目 B 已有 TC-009 → 全库 max=9，下一个是 TC-010
        when(repository.findAll()).thenReturn(List.of(
                tc("TC-005", "projA"), tc("TC-009", "projB")));

        assertEquals("TC-010", allocator.nextId());
    }

    @Test
    void sequentialAllocationNeverRepeats() {
        String first = allocator.nextId();
        String second = allocator.nextId();
        String third = allocator.nextId();

        // 空库冷启动：TC-001/002/003 连续递增，无重复
        assertEquals("TC-001", first);
        assertEquals("TC-002", second);
        assertEquals("TC-003", third);
    }

    @Test
    void resetCacheReloadsFromDb() {
        assertEquals("TC-001", allocator.nextId());

        // 模拟其他进程写入更高编号后重置缓存（单实例部署内的显式失效路径）
        when(repository.findAll()).thenReturn(List.of(tc("TC-042", "projA")));
        ReflectionTestUtils.invokeMethod(allocator, "resetCache");

        assertEquals("TC-043", allocator.nextId());
    }

    @Test
    void parseSuffixHandlesEdgeCases() {
        assertEquals(7, TestCaseIdAllocator.parseSuffix("TC-007"));
        assertEquals(0, TestCaseIdAllocator.parseSuffix(null));
        assertEquals(0, TestCaseIdAllocator.parseSuffix("CASE-7"));
        assertEquals(0, TestCaseIdAllocator.parseSuffix("TC-abc"));
    }

    @Test
    void formatIsZeroPaddedThreeDigits() {
        String id = allocator.nextId();
        assertTrue(id.matches("TC-\\d{3}"), "ID 应为 TC-xxx 三位零填充格式: " + id);
    }
}

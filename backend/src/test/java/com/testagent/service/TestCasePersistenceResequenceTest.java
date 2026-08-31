package com.testagent.service;

import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v9.3: 项目内展示序号按模块归组重编——LLM 按覆盖端点产出用例，同模块在编号轴上被打散，
 * 前端按 module 分组渲染时组内跳号（1,2,13,14）观感为"乱序"。
 * 重编规则：模块按首次出现排序，组内保持原相对顺序。
 */
@ExtendWith(MockitoExtension.class)
class TestCasePersistenceResequenceTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @InjectMocks
    private TestCasePersistenceService persistenceService;

    private TestCase tc(String id, int seq, String module) {
        TestCase t = new TestCase();
        t.setId(id);
        t.setProjectId("p1");
        t.setProjectSeq(seq);
        t.setModule(module);
        t.setTitle("用例" + seq);
        return t;
    }

    @Test
    void resequenceGroupsByModuleFirstAppearanceKeepsInnerOrder() {
        // 库内序号（生成产出序）：收藏/足迹/收藏/会员/足迹/收藏——同模块被打散
        List<TestCase> rows = new ArrayList<>(List.of(
                tc("TC-1", 1, "收藏"),
                tc("TC-2", 2, "足迹"),
                tc("TC-3", 3, "收藏"),
                tc("TC-4", 4, "会员"),
                tc("TC-5", 5, "足迹"),
                tc("TC-6", 6, "收藏")));
        when(testCaseRepository.findByProjectId("p1")).thenReturn(rows);

        persistenceService.resequenceProjectSeq("p1");

        // 模块序=首次出现序（收藏→足迹→会员），组内保持原相对顺序，组内编号连续
        assertEquals(1, rows.get(0).getProjectSeq());  // TC-1 收藏
        assertEquals(2, rows.get(2).getProjectSeq());  // TC-3 收藏
        assertEquals(3, rows.get(5).getProjectSeq());  // TC-6 收藏
        assertEquals(4, rows.get(1).getProjectSeq());  // TC-2 足迹
        assertEquals(5, rows.get(4).getProjectSeq());  // TC-5 足迹
        assertEquals(6, rows.get(3).getProjectSeq());  // TC-4 会员
        verify(testCaseRepository).saveAll(rows);
    }

    @Test
    void nullOrBlankModuleFallsIntoUncategorized() {
        List<TestCase> rows = new ArrayList<>(List.of(
                tc("TC-1", 1, null),
                tc("TC-2", 2, "收藏"),
                tc("TC-3", 3, "  ")));
        when(testCaseRepository.findByProjectId("p1")).thenReturn(rows);

        persistenceService.resequenceProjectSeq("p1");

        // 模块序=首次出现序：TC-1(null) 归入"未分类"且最先出现 → 未分类组在前；两个空模块同组
        assertEquals(1, rows.get(0).getProjectSeq());  // TC-1 未分类
        assertEquals(2, rows.get(2).getProjectSeq());  // TC-3 未分类
        assertEquals(3, rows.get(1).getProjectSeq());  // TC-2 收藏
    }

    @Test
    void fewerThanTwoCasesIsNoop() {
        when(testCaseRepository.findByProjectId("p1")).thenReturn(List.of(tc("TC-1", 1, "收藏")));

        persistenceService.resequenceProjectSeq("p1");

        verify(testCaseRepository, never()).saveAll(any());
    }
}

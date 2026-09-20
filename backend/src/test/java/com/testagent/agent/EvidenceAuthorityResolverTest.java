package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.17(证据权威判定): 判定矩阵单测。
 * v13.19(用例来源原则): 矩阵收敛——"代码有、PRD 无"一律不生成；变更中一律暂缓。
 *
 * <p>核心断言：权威归属是（需求状态 × 冲突方向 × 新鲜度）的函数，而非全局常量。
 */
class EvidenceAuthorityResolverTest {

    private String auth(String state, String direction, boolean stale) {
        return EvidenceAuthorityResolver.resolve(state, direction, stale).authority();
    }

    @Test
    void newRequirementIsAlwaysPrdExceptCodeOnly() {
        // 新增需求：代码在 PRD 之后实现 —— 一律以 PRD 为准
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD,
                auth(EvidenceAuthorityResolver.STATE_NEW, EvidenceAuthorityResolver.DIR_PRD_ONLY, false));
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD,
                auth(EvidenceAuthorityResolver.STATE_NEW, EvidenceAuthorityResolver.DIR_MIXED, false));
        // 代码多出、PRD 未描述：无需求依据 → 不生成（不是"以代码为准"）
        assertEquals(EvidenceAuthorityResolver.AUTH_SKIP,
                auth(EvidenceAuthorityResolver.STATE_NEW, EvidenceAuthorityResolver.DIR_CODE_ONLY, false));
    }

    @Test
    void modifiedRequirementIsDeferredNotHuman() {
        // 变更中：PRD 未定稿，裁决无意义 → 暂缓，等 PRD 更新后重跑（不打扰人）
        for (String direction : List.of(EvidenceAuthorityResolver.DIR_PRD_ONLY,
                EvidenceAuthorityResolver.DIR_CODE_ONLY, EvidenceAuthorityResolver.DIR_MIXED)) {
            for (boolean stale : List.of(true, false)) {
                assertEquals(EvidenceAuthorityResolver.AUTH_DEFERRED,
                        auth(EvidenceAuthorityResolver.STATE_MODIFIED, direction, stale),
                        "变更中需求一律暂缓: " + direction + "/" + stale);
            }
        }
    }

    @Test
    void codeOnlyNeverProducesCasesRegardlessOfStateOrStaleness() {
        // v13.19 核心原则：用例只来源于 PRD。代码多出的状态没有需求依据，
        // 新增与存量、stale 与否，一律不生成用例。
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW, EvidenceAuthorityResolver.STATE_STABLE)) {
            for (boolean stale : List.of(true, false)) {
                assertEquals(EvidenceAuthorityResolver.AUTH_SKIP,
                        auth(state, EvidenceAuthorityResolver.DIR_CODE_ONLY, stale),
                        "代码有 PRD 无一律不生成: " + state + "/stale=" + stale);
            }
        }
    }

    @Test
    void matrixNoLongerProducesCodeAuthority() {
        // 矩阵不再产出 code —— 自动"以代码为准"已被取消（人工裁决仍可显式选它）
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW,
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.STATE_STABLE)) {
            for (String direction : List.of(EvidenceAuthorityResolver.DIR_PRD_ONLY,
                    EvidenceAuthorityResolver.DIR_CODE_ONLY, EvidenceAuthorityResolver.DIR_MIXED)) {
                for (boolean stale : List.of(true, false)) {
                    assertNotEquals(EvidenceAuthorityResolver.AUTH_CODE,
                            auth(state, direction, stale),
                            "矩阵不得再自动产出 code 权威: " + state + "/" + direction + "/stale=" + stale);
                }
            }
        }
    }

    @Test
    void stableCellWithoutFreshPrdIsSkippedUnlessPrdIsNewer() {
        // v13.21: 存量稳定不再提请人工——属历史功能，回归用例已覆盖，本期不重复生成
        assertEquals(EvidenceAuthorityResolver.AUTH_SKIP,
                auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_PRD_ONLY, false),
                "存量稳定下 PRD 有代码无：不再人工裁决，直接不生成");
        assertEquals(EvidenceAuthorityResolver.AUTH_SKIP,
                auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_MIXED, false));
        // 时序兜底保留：PRD 晚于代码且元素未实现 → 视为新增未实现，以 PRD 为准
        assertEquals(EvidenceAuthorityResolver.AUTH_PRD,
                auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_PRD_ONLY, true),
                "PRD 晚于代码且元素未实现，按时序兜底判为新增需求，以 PRD 为准");
    }

    @Test
    void matrixNoLongerProducesHuman() {
        // v13.21: 矩阵纯自动——存量稳定不再纳入人工，human 彻底退出矩阵。
        // （人工裁决链路完整保留，但不再有自动触发源；见 EvidenceAuthorityResolver.AUTH_HUMAN 注释）
        int humanCells = 0;
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW,
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.STATE_STABLE)) {
            for (String direction : List.of(EvidenceAuthorityResolver.DIR_PRD_ONLY,
                    EvidenceAuthorityResolver.DIR_CODE_ONLY, EvidenceAuthorityResolver.DIR_MIXED)) {
                for (boolean stale : List.of(true, false)) {
                    if (EvidenceAuthorityResolver.AUTH_HUMAN.equals(auth(state, direction, stale))) {
                        humanCells++;
                    }
                }
            }
        }
        assertEquals(0, humanCells, "矩阵不得再产出 human：存量稳定不再提请人工");
    }

    @Test
    void onlyNewRequirementOrFreshPrdProducesCases() {
        // 全矩阵只有"以 PRD 为准"会产出用例，且只应出现在两类场景：
        // ①新增需求（代码在 PRD 之后实现，可能跑偏）②存量稳定但需求资料更新（时序兜底）
        int prdCells = 0;
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW,
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.STATE_STABLE)) {
            for (String direction : List.of(EvidenceAuthorityResolver.DIR_PRD_ONLY,
                    EvidenceAuthorityResolver.DIR_CODE_ONLY, EvidenceAuthorityResolver.DIR_MIXED)) {
                for (boolean stale : List.of(true, false)) {
                    if (EvidenceAuthorityResolver.AUTH_PRD.equals(auth(state, direction, stale))) {
                        prdCells++;
                        assertTrue(EvidenceAuthorityResolver.STATE_NEW.equals(state) || stale,
                                "以 PRD 为准只应出现在新增需求或需求资料更新的存量场景: "
                                        + state + "/" + direction + "/stale=" + stale);
                    }
                }
            }
        }
        // 新增需求 4 格（PRD_ONLY/MIXED × stale 两种，NEW 分支不判 stale）
        // + 存量稳定且需求资料更新 2 格（PRD_ONLY/MIXED）
        assertEquals(6, prdCells, "新增 4 格 + 存量且需求更新 2 格");
    }

    @Test
    void unknownOrBlankStateFallsBackToStable() {
        assertEquals(auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_PRD_ONLY, false),
                auth(null, EvidenceAuthorityResolver.DIR_PRD_ONLY, false));
        assertEquals(auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_CODE_ONLY, false),
                auth("", EvidenceAuthorityResolver.DIR_CODE_ONLY, false));
        assertEquals(auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_CODE_ONLY, false),
                auth("unknown-state", EvidenceAuthorityResolver.DIR_CODE_ONLY, false));
    }

    @Test
    void nullDirectionFallsBackToPrdOnly() {
        assertEquals(auth(EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_PRD_ONLY, false),
                auth(EvidenceAuthorityResolver.STATE_STABLE, null, false));
    }

    @Test
    void manualRequiredIsFalseForEveryCell() {
        // v13.21: human 退出矩阵后 manualRequired 恒 false ——
        // 用例侧 pending 标记与前端「待裁决」视图不再由自动判定触发
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW,
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.STATE_STABLE)) {
            for (String direction : List.of(EvidenceAuthorityResolver.DIR_PRD_ONLY,
                    EvidenceAuthorityResolver.DIR_CODE_ONLY, EvidenceAuthorityResolver.DIR_MIXED)) {
                for (boolean stale : List.of(true, false)) {
                    EvidenceAuthorityResolver.Verdict v =
                            EvidenceAuthorityResolver.resolve(state, direction, stale);
                    assertEquals(EvidenceAuthorityResolver.AUTH_HUMAN.equals(v.authority()), v.manualRequired(),
                            "manualRequired 当且仅当 human: " + state + "/" + direction + "/stale=" + stale);
                    assertFalse(v.manualRequired(),
                            "v13.21 后不应有任何格子需要人: " + state + "/" + direction + "/stale=" + stale);
                }
            }
        }
        assertFalse(EvidenceAuthorityResolver.resolve(
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.DIR_PRD_ONLY, false)
                .manualRequired(), "变更中已改为暂缓，不再需要人");
    }

    @Test
    void reasonIsHumanReadableForEveryCell() {
        for (String state : List.of(EvidenceAuthorityResolver.STATE_NEW,
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.STATE_STABLE)) {
            for (String direction : List.of(EvidenceAuthorityResolver.DIR_PRD_ONLY,
                    EvidenceAuthorityResolver.DIR_CODE_ONLY, EvidenceAuthorityResolver.DIR_MIXED)) {
                for (boolean stale : List.of(true, false)) {
                    String reason = EvidenceAuthorityResolver.resolve(state, direction, stale).reason();
                    assertTrue(reason != null && !reason.isBlank(),
                            "每个判定格都必须给出人读理由: " + state + "/" + direction + "/" + stale);
                }
            }
        }
    }

    @Test
    void skipAndDeferredExplainWhyNoCaseIsProduced() {
        String skipReason = EvidenceAuthorityResolver.resolve(
                EvidenceAuthorityResolver.STATE_STABLE, EvidenceAuthorityResolver.DIR_CODE_ONLY, false).reason();
        assertTrue(skipReason.contains("PRD"), "skip 的理由应点明缺少 PRD 依据: " + skipReason);

        String deferredReason = EvidenceAuthorityResolver.resolve(
                EvidenceAuthorityResolver.STATE_MODIFIED, EvidenceAuthorityResolver.DIR_PRD_ONLY, false).reason();
        assertTrue(deferredReason.contains("定稿") || deferredReason.contains("暂缓"),
                "deferred 的理由应点明等 PRD 定稿: " + deferredReason);
    }
}

package com.testagent.agent;

/**
 * v13.17(证据权威判定): 把"谁是权威"从全局常量（旧行为：恒以代码为准）改为随上下文变化的函数。
 *
 * <p>v13.19(用例来源原则): <b>用例只来源于 PRD</b>——生成侧 prompt 即"以 PRD 文档为纲…
 * 代码信息用于补充接口路径与前置状态"，且项目级已要求"无 PRD 不可生成"
 * （前端 {@code ProjectDetail.canGenerate} 在 {@code !hasPrd} 时禁用生成入口）。
 * 故"代码有、PRD 无"不构成产出用例的场景，一律 {@link #AUTH_SKIP}（仅记录为文档缺口）；
 * 需求变更中则 {@link #AUTH_DEFERRED}（PRD 未定稿，等定稿后重跑）。
 *
 * <p>v13.21(零人工): 存量稳定不再产出 {@link #AUTH_HUMAN}——存量功能的回归用例早已存在，
 * 本期生成只针对新增/变更范围，为历史差异提请人工没有产出价值。至此矩阵**纯自动**，
 * 只输出 {@code prd} / {@code skip} / {@code deferred} 三值。
 *
 * <p>判定输入三个维度：
 * <ul>
 *   <li>{@code requirementState}——需求状态（NEW 新增 / MODIFIED 变更中 / STABLE 存量稳定），
 *       来源为本期范围 {@code ScopeItem.changeKind}；无范围数据时默认 STABLE。</li>
 *   <li>{@code direction}——冲突方向（PRD_ONLY：PRD 有代码无 / CODE_ONLY：代码有 PRD 无 / MIXED：两侧均有）。</li>
 *   <li>{@code stale}——需求资料是否晚于代码侧产物（{@code evidenceStale}）。</li>
 * </ul>
 *
 * <p>判定矩阵（行=需求状态，列=冲突方向）：
 * <pre>
 *              PRD 有·代码无      代码有·PRD 无       两侧均有差异
 *   新增       以 PRD 为准        不生成              以 PRD 为准
 *   变更中     暂缓               暂缓                暂缓
 *   存量稳定   不生成 *           不生成              不生成 *
 *   （* 需求资料晚于代码侧时，按"新增未实现"处理，以 PRD 为准）
 * </pre>
 *
 * <p>三条关键原则：
 * <ul>
 *   <li><b>代码不是用例来源</b>：没有 PRD 描述的元素不产出用例，只记录为"文档缺口"。</li>
 *   <li><b>变更中不打扰人</b>：PRD 未定稿时裁决无意义，等定稿后按 PRD 重跑即可。</li>
 *   <li><b>存量不重复生成</b>：存量稳定属历史功能，回归用例已覆盖，本期不再产出。</li>
 * </ul>
 *
 * <p>时序兜底（无范围数据项目的主要路径）：需求资料晚于代码侧
 * → 视为"PRD 更新后新增的未实现元素"，以 PRD 为准。
 *
 * <p>纯函数，无 IO，便于单测。
 */
public final class EvidenceAuthorityResolver {

    /** 以 PRD 为准：按 PRD 描述生成用例（代码可能未实现或实现跑偏） */
    public static final String AUTH_PRD = "prd";
    /**
     * 以代码为准：v13.19 起**矩阵不再产出**此值（用例只来源于 PRD，代码多出的状态不产出用例）。
     * <p>保留该常量的原因：①兼容历史落库记录（{@code evidence_conflicts.authority} 已存有 code 值）
     * ②<b>人工裁决</b>仍可显式选择"以代码为准"——那是人的例外判断，与机器自动判定不同。
     */
    public static final String AUTH_CODE = "code";
    /**
     * 需人工裁决：v13.21 起**矩阵不再产出**此值（存量稳定不再提请人工）。
     * <p>保留该常量的原因：①兼容历史落库记录（{@code evidence_conflicts.authority} 已存有 human 值），
     * 人工裁决链路（清单接口 / 裁决 API / 用例 {@code pending} 标记）完整保留、未删除；
     * ②未来扩展 ENDPOINT / PAGE 维度若出现"机器判不了"的分歧，可重新启用。
     */
    public static final String AUTH_HUMAN = "human";
    /** 不生成用例：无 PRD 依据（代码多出的状态只记录为文档缺口，不产出用例） */
    public static final String AUTH_SKIP = "skip";
    /** 暂缓：需求变更中、PRD 尚未定稿，等定稿后重跑 */
    public static final String AUTH_DEFERRED = "deferred";

    public static final String STATE_NEW = "NEW";
    public static final String STATE_MODIFIED = "MODIFIED";
    public static final String STATE_STABLE = "STABLE";

    public static final String DIR_PRD_ONLY = "PRD_ONLY";
    public static final String DIR_CODE_ONLY = "CODE_ONLY";
    public static final String DIR_MIXED = "MIXED";

    private EvidenceAuthorityResolver() {
    }

    /** 判定结果：权威归属 + 是否需人工裁决 + 人读理由 */
    public record Verdict(String authority, boolean manualRequired, String reason) {
    }

    /**
     * 查判定矩阵得出权威归属。
     *
     * @param requirementState NEW / MODIFIED / STABLE（null 或未知按 STABLE 处理）
     * @param direction        PRD_ONLY / CODE_ONLY / MIXED
     * @param stale            需求资料是否晚于代码侧产物
     */
    public static Verdict resolve(String requirementState, String direction, boolean stale) {
        String state = requirementState == null || requirementState.isBlank()
                ? STATE_STABLE : requirementState.trim().toUpperCase();
        String dir = direction == null || direction.isBlank()
                ? DIR_PRD_ONLY : direction.trim().toUpperCase();

        // 变更中：PRD 尚未定稿，此刻生成没有意义 → 暂缓，等 PRD 更新后重跑
        // （实际上 PRD 变更中的项目生成入口本就不可用，此分支为防御性兜底）
        if (STATE_MODIFIED.equals(state)) {
            return deferred("需求变更中：PRD 尚未定稿，暂缓生成，等 PRD 更新后重跑");
        }

        // 新增：代码是 PRD 之后才实现的，可能未充分理解业务意图 → 一律以 PRD 为准
        if (STATE_NEW.equals(state)) {
            if (DIR_CODE_ONLY.equals(dir)) {
                return skip("新增需求：代码有、PRD 无，无需求依据不生成用例"
                        + "（疑实现跑偏，建议复核或补 PRD）");
            }
            return prd("新增需求：以 PRD 为准（代码可能尚未实现，或已实现但与业务意图不符）");
        }

        // 存量稳定
        if (DIR_CODE_ONLY.equals(dir)) {
            // 用例只来源于 PRD：代码多出的状态没有需求依据 → 不产出用例
            return skip("存量需求：代码有、PRD 无，属文档滞后，无需求依据不生成用例（建议补 PRD）");
        }
        // v13.21: 时序兜底——需求资料晚于代码分析，说明需求侧有新变化，
        // 该元素按"新增未实现"处理，以 PRD 为准（放行于存量过滤之前）
        if (stale) {
            return prd("需求资料晚于代码侧，按新增需求处理，以 PRD 为准");
        }
        // v13.21: 存量稳定 + 需求资料不新 → 属历史功能，回归用例已覆盖，本期不重复生成。
        // 原判 human（漏实现 vs 需求已废弃，机器判不了）；现按"存量不产出用例"处理，
        // human 由此彻底退出矩阵。判断依据：本期生成本就只针对新增/变更范围，
        // 为存量差异提请人工既无产出价值、也无人愿意裁决。
        return skip("存量稳定：属历史功能，回归用例已覆盖，本期不重复生成");
    }

    /** 需人工裁决（v13.21 起矩阵不产出，保留供未来维度启用，见 {@link #AUTH_HUMAN}） */
    private static Verdict human(String reason) {
        return new Verdict(AUTH_HUMAN, true, reason);
    }

    private static Verdict prd(String reason) {
        return new Verdict(AUTH_PRD, false, reason);
    }

    private static Verdict skip(String reason) {
        return new Verdict(AUTH_SKIP, false, reason);
    }

    private static Verdict deferred(String reason) {
        return new Verdict(AUTH_DEFERRED, false, reason);
    }
}

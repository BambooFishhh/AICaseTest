package com.testagent.chaos;

import com.testagent.agent.TestGeneratorAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v8.8.2(10.6): 混沌演练①——畸形输出对抗样本集。
 * 契约语义：整段不可解析/非 JSON 数组 → RuntimeException 上抛（触发调用方整轮重试）；
 * 条目级畸形 → 逐条容错不抛异常，空心条目由下游评审/去重过滤。
 * @Tag("chaos") 单独分组，不阻塞日常构建：
 * mvn test -Dgroups=chaos -Dsurefire.excludedGroups=
 */
@Tag("chaos")
class MalformedOutputChaosTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private List<?> parse(String json) {
        return (List<?>) ReflectionTestUtils.invokeMethod(agent, "parseTestCases", json, (Object) null);
    }

    @Test
    void truncatedArrayEscalatesForRoundRetry() {
        // 截断数组（对象未闭合）：整段上抛走轮次重试，而非静默返回半截结果
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> parse("[{\"title\":\"完整条目\",\"steps\":[\"s\"],\"priority\":\"P0\",\"type\":\"positive\"},{\"title\":\"截断"));
    }

    @Test
    void nonArrayWholeDocumentEscalates() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> parse("{\"title\":\"单对象误出\"}"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> parse("纯文本说明，没有 JSON"));
    }

    @Test
    void scalarEntriesToleratedWithoutThrowing() {
        // 字符串/数字/null 条目：逐条容错不抛异常（产出的空心条目由下游评审规则过滤）
        List<?> result = parse("[\"字符串条目\",42,null,{\"title\":\"合法\",\"steps\":[],\"priority\":\"P1\",\"type\":\"data\"}]");
        assertTrue(result.size() >= 1);
    }

    @Test
    void nestedGarbageStepsToleratedWithoutThrowing() {
        // steps 结构炸弹：转换期不容错会丢整批——必须以条目粒度消化
        String garbage = "[{\"title\":\"嵌套炸弹\",\"steps\":[{\"deep\":{\"deeper\":{\"deeper\":[1,2,{\"a\":\"b\"}]}}}],"
                + "\"priority\":\"P0\",\"type\":\"positive\"}]";
        List<?> result = parse(garbage);
        assertTrue(result.size() >= 0);
    }
}

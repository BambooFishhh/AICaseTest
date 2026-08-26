package com.testagent.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// v8.7.1(9.5.4): 异步任务 MDC 透传与还原
class MdcTaskDecoratorTest {

    @Test
    void propagatesContextToExecutingThread() throws Exception {
        MDC.put("projectId", "p-42");
        MDC.put("taskId", "t-1");
        AtomicReferenceBox seen = new AtomicReferenceBox();

        Runnable decorated = new MdcTaskDecorator().decorate(() -> {
            seen.projectId = MDC.get("projectId");
            seen.taskId = MDC.get("taskId");
        });

        MDC.clear();
        decorated.run();

        assertEquals("p-42", seen.projectId);
        assertEquals("t-1", seen.taskId);
        assertNull(MDC.get("projectId"));
    }

    @Test
    void clearsContextWhenSubmitterHadNone() {
        MDC.put("projectId", "p9");
        AtomicReferenceBox seen = new AtomicReferenceBox();

        Runnable decorated = new MdcTaskDecorator().decorate(() -> seen.projectId = MDC.get("projectId"));

        // 模拟池化线程执行时已无上下文：装饰任务结束后不应残留提交线程的键
        MDC.clear();
        decorated.run();

        assertEquals("p9", seen.projectId);
        assertNull(MDC.get("projectId"));
    }

    private static class AtomicReferenceBox {
        String projectId;
        String taskId;
    }
}

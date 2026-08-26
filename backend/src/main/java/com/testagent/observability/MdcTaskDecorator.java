package com.testagent.observability;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * v8.7.1(9.5.4): 异步任务 MDC 透传——提交线程的 taskId/projectId 上下文
 * 复制到执行线程，执行结束还原执行线程原上下文，防池化线程上下文泄漏。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) {
                MDC.setContextMap(captured);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}

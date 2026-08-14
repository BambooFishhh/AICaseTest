package com.testagent.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * vT1: 内存任务队列基线测试。
 */
class MemoryTaskQueueStoreTest {

    private final MemoryTaskQueueStore store = new MemoryTaskQueueStore();

    @Test
    void enqueueThenRunningThenDone() {
        store.enqueue("generation", "p1");
        assertEquals(1, store.queuedCount("generation"));
        assertEquals(0, store.runningCount("generation"));

        store.markRunning("generation", "p1");
        assertEquals(0, store.queuedCount("generation"));
        assertEquals(1, store.runningCount("generation"));

        store.markDone("generation", "p1");
        assertEquals(0, store.queuedCount("generation"));
        assertEquals(0, store.runningCount("generation"));
    }

    @Test
    void multipleTasksCountIndependently() {
        store.enqueue("execution", "e1");
        store.enqueue("execution", "e2");
        store.markRunning("execution", "e1");

        assertEquals(1, store.queuedCount("execution"));
        assertEquals(1, store.runningCount("execution"));
        assertEquals(0, store.queuedCount("generation"));
    }

    @Test
    void markDoneIsIdempotent() {
        store.enqueue("execution", "e1");
        store.markDone("execution", "e1");
        store.markDone("execution", "e1");
        assertEquals(0, store.queuedCount("execution"));
        assertEquals(0, store.runningCount("execution"));
    }

    @Test
    void clearQueueRemovesQueuedAndRunning() {
        store.enqueue("generation", "p1");
        store.markRunning("generation", "p2");
        store.clearQueue("generation");

        assertEquals(0, store.queuedCount("generation"));
        assertEquals(0, store.runningCount("generation"));
    }
}

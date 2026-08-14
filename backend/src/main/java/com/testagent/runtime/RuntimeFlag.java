package com.testagent.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v5.2: 可持久化到 RuntimeStore 的取消标志。
 * isCancelled() 同时检查本地值与存储层（Redis/内存），cancel() 同步写入存储层，支持多实例取消。
 */
public class RuntimeFlag implements CancellationSignal {

    private final String key;
    private final RuntimeStore store;
    private final AtomicBoolean local = new AtomicBoolean(false);

    public RuntimeFlag(String key, RuntimeStore store) {
        this.key = key;
        this.store = store;
    }

    @Override
    public boolean isCancelled() {
        return local.get() || store.isFlagSet(key);
    }

    @Override
    public void cancel() {
        local.set(true);
        store.setFlag(key, true);
    }

    public void clear() {
        local.set(false);
        store.clearFlag(key);
    }
}

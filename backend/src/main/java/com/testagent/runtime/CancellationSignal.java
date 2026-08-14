package com.testagent.runtime;

/**
 * v5.2: 取消信号抽象，供生成/执行链路检查与触发取消。
 */
public interface CancellationSignal {

    boolean isCancelled();

    void cancel();
}

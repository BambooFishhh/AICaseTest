package com.testagent.common;

/**
 * v3.3: 用例生成被取消时抛出。runGenerateStream catch 后跳过落库、恢复状态。
 */
public class GenerationCancelledException extends RuntimeException {
    public GenerationCancelledException(String message) {
        super(message);
    }
}

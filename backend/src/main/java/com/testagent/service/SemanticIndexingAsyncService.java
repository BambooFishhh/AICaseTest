package com.testagent.service;

import com.testagent.entity.TestCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 语义索引异步化：编辑用例时 embedding 可能被生成任务占用的 MCP 连接阻塞，
 * 放到独立线程池执行，避免保存请求长时间等待。
 */
@Service
public class SemanticIndexingAsyncService {

    @Autowired
    private SemanticService semanticService;

    @Async("semanticExecutor")
    public void reindexCase(String projectId, TestCase tc) {
        semanticService.reindexCase(projectId, tc);
    }
}

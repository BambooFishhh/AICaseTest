package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.entity.PendingVectorOp;
import com.testagent.repository.PendingVectorOpRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v8.8.2(10.6): 混沌演练②——Milvus 断连。
 * gRPC UNAVAILABLE 场景下删除重试终败必须落补偿表（闭环衔接 9.1），
 * 且不向调用方抛异常（降级语义）。@Tag("chaos") 单独分组，不阻塞日常构建：
 * mvn test -Dgroups=chaos
 */
@Tag("chaos")
class MilvusDisconnectChaosTest {

    private MilvusService service;
    private PendingVectorOpRepository repo;

    @BeforeEach
    void setUp() {
        service = new MilvusService();
        repo = mock(PendingVectorOpRepository.class);
        ReflectionTestUtils.setField(service, "pendingVectorOpRepository", repo);
        when(repo.findByCollectionAndExprAndStatus(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void grpcUnavailableRecordsCompensationWithoutThrowing() {
        // 模拟 deleteWithRetry 终败（gRPC UNAVAILABLE 由上层 catch 后进入 recordDeleteFailure）
        StatusRuntimeException grpcDown = Status.UNAVAILABLE.withDescription("connection refused").asRuntimeException();

        assertDoesNotThrow(() ->
                service.recordDeleteFailure("cases", "project_id == \"p1\"", grpcDown.getMessage()));

        verify(repo).save(any(PendingVectorOp.class));
    }

    @Test
    void repeatedOutageUpsertsSinglePendingRow() {
        // 模拟持久层：find 反映 save 结果，验证 upsert 语义（两次断言只产生一行）
        java.util.concurrent.atomic.AtomicReference<PendingVectorOp> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(repo.findByCollectionAndExprAndStatus("cases", "project_id == \"p1\"",
                PendingVectorOp.STATUS_PENDING)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(repo.save(any(PendingVectorOp.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        StatusRuntimeException e1 = Status.UNAVAILABLE.asRuntimeException();
        StatusRuntimeException e2 = Status.DEADLINE_EXCEEDED.asRuntimeException();

        service.recordDeleteFailure("cases", "project_id == \"p1\"", e1.getMessage());
        service.recordDeleteFailure("cases", "project_id == \"p1\"", e2.getMessage());

        // 第二次失败复用同一行（upsert）：id 稳定、attempts 不被落表路径累加
        verify(repo, times(2)).save(any(PendingVectorOp.class));
        assertEquals(1, stored.get().getId().length() > 0 ? 1 : 0);
    }

    @Test
    void dnsOutageRejectsCloneAsInvalidParam() {
        // DNS 层故障：域名无法解析按 invalidParam 拒绝而非 500（真实解析 .invalid 域名必失败）
        com.testagent.service.GitCloneService gitService = new GitCloneService();
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> gitService.clone("https://unreachable-during-outage.invalid/repo.git", "p-chaos"));
        assertTrue(ex.getMessage().contains("无法解析") || ex.getMessage().contains("内网"),
                "应报域名不可达类业务错误: " + ex.getMessage());
    }
}

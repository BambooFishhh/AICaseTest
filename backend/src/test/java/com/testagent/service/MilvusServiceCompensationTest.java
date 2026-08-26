package com.testagent.service;

import com.testagent.entity.PendingVectorOp;
import com.testagent.repository.PendingVectorOpRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// v8.6.1(9.1): 删除终败落补偿表——upsert 语义，同 collection+expr 不堆行
class MilvusServiceCompensationTest {

    private MilvusService serviceWith(PendingVectorOpRepository repo) {
        MilvusService service = new MilvusService();
        ReflectionTestUtils.setField(service, "pendingVectorOpRepository", repo);
        return service;
    }

    @Test
    void firstFailureInsertsPendingRow() {
        PendingVectorOpRepository repo = mock(PendingVectorOpRepository.class);
        when(repo.findByCollectionAndExprAndStatus("cases", "project_id == \"p1\"",
                PendingVectorOp.STATUS_PENDING)).thenReturn(Optional.empty());
        MilvusService service = serviceWith(repo);

        service.recordDeleteFailure("cases", "project_id == \"p1\"", "timeout");

        verify(repo, times(1)).save(any(PendingVectorOp.class));
    }

    @Test
    void repeatedFailureUpdatesExistingRowInsteadOfDuplicating() {
        PendingVectorOpRepository repo = mock(PendingVectorOpRepository.class);
        PendingVectorOp existing = new PendingVectorOp();
        existing.setId("op-1");
        when(repo.findByCollectionAndExprAndStatus("cases", "project_id == \"p1\"",
                PendingVectorOp.STATUS_PENDING)).thenReturn(Optional.of(existing));
        MilvusService service = serviceWith(repo);

        service.recordDeleteFailure("cases", "project_id == \"p1\"", "timeout again");

        // 更新既有行而非插入新行（id 未变）
        verify(repo, times(1)).save(existing);
        verify(repo, never()).save(org.mockito.ArgumentMatchers.argThat(
                op -> op != null && !"op-1".equals(op.getId())));
    }

    @Test
    void nullRepositoryKeepsLegacyLogOnlyBehavior() {
        MilvusService service = new MilvusService();
        // 不注入仓库：仅日志，不抛异常
        service.recordDeleteFailure("cases", "project_id == \"p1\"", "boom");
    }
}

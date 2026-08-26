package com.testagent.service;

import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * v8.9.2(12.5): 向量 id 分页拉取——两页合并、页满继续、不足页终止、单页失败整体 null。
 * 通过覆写包级私有 queryIdPage 注入页序列，无需真实 Milvus。
 */
class MilvusQueryPaginationTest {

    @Test
    void fullPageTriggersNextOffsetUntilShortPage() {
        List<String> recordedOffsets = new ArrayList<>();
        MilvusService service = new MilvusService() {
            @Override
            List<String> queryIdPage(MilvusServiceClient c, String collection, String expr,
                                     long offset, int limit) {
                recordedOffsets.add(String.valueOf(offset));
                assertEquals(1000, limit);
                int pageSeq = offset == 0 ? 1 : 2;
                List<String> page = new ArrayList<>();
                int count = pageSeq == 1 ? limit : 200;
                for (int i = 0; i < count; i++) {
                    page.add("TC-" + pageSeq + "-" + i);
                }
                return page;
            }
        };

        List<String> all = service.queryIdsPaged(null, "cases", "project_id == \"p1\"");

        assertEquals(1200, all.size());
        assertEquals(List.of("0", "1000"), recordedOffsets);
    }

    @Test
    void singleShortPageReturnsImmediately() {
        MilvusService service = new MilvusService() {
            @Override
            List<String> queryIdPage(MilvusServiceClient c, String collection, String expr,
                                     long offset, int limit) {
                return List.of("TC-1", "TC-2");
            }
        };

        List<String> all = service.queryIdsPaged(null, "cases", "expr");
        assertEquals(2, all.size());
    }

    @Test
    void pageFailurePropagatesAsNull() {
        MilvusService service = new MilvusService() {
            @Override
            List<String> queryIdPage(MilvusServiceClient c, String collection, String expr,
                                     long offset, int limit) {
                return null;
            }
        };

        assertNull(service.queryIdsPaged(null, "cases", "expr"));
    }
}

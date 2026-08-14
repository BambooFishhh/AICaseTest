package com.testagent.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v5.4: Milvus 向量检索服务。默认关闭，开启且连接可用时自动建集合。
 */
@Service
public class MilvusService {

    private static final Logger log = LoggerFactory.getLogger(MilvusService.class);

    public static final String COLLECTION_CASES = "cases";
    public static final String COLLECTION_CONTEXTS = "contexts";
    public static final String COLLECTION_FAILURES = "failures";

    @Value("${app.milvus.enabled:false}")
    private boolean enabled;

    @Value("${app.milvus.host:localhost}")
    private String host;

    @Value("${app.milvus.port:19530}")
    private int port;

    @Value("${app.milvus.dimension:1536}")
    private int dimension;

    @Value("${app.milvus.duplicate-threshold:0.92}")
    private double duplicateThreshold;

    private volatile MilvusServiceClient client;

    public boolean isEnabled() {
        return enabled;
    }

    public double duplicateThreshold() {
        return duplicateThreshold;
    }

    private MilvusServiceClient client() {
        if (!enabled) {
            return null;
        }
        MilvusServiceClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    try {
                        c = new MilvusServiceClient(
                                ConnectParam.newBuilder().withHost(host).withPort(port).build());
                        ensureCollection(c, COLLECTION_CASES);
                        ensureCollection(c, COLLECTION_CONTEXTS);
                        ensureCollection(c, COLLECTION_FAILURES);
                        client = c;
                        log.info("Milvus connected: {}:{}", host, port);
                    } catch (Exception e) {
                        log.warn("Milvus unavailable, semantic features disabled: {}", e.getMessage());
                        client = null;
                        return null;
                    }
                }
            }
        }
        return c;
    }

    private void ensureCollection(MilvusServiceClient c, String name) {
        try {
            R<Boolean> exists = c.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(name).build());
            if (exists != null && Boolean.TRUE.equals(exists.getData())) {
                return;
            }
            CreateCollectionParam param = CreateCollectionParam.newBuilder()
                    .withCollectionName(name)
                    .withDescription(name + " semantic collection")
                    .withShardsNum(1)
                    .addFieldType(FieldType.newBuilder().withName("id")
                            .withDataType(DataType.VarChar).withMaxLength(128).withPrimaryKey(true).build())
                    .addFieldType(FieldType.newBuilder().withName("project_id")
                            .withDataType(DataType.VarChar).withMaxLength(64).build())
                    .addFieldType(FieldType.newBuilder().withName("title")
                            .withDataType(DataType.VarChar).withMaxLength(512).build())
                    .addFieldType(FieldType.newBuilder().withName("module")
                            .withDataType(DataType.VarChar).withMaxLength(128).build())
                    .addFieldType(FieldType.newBuilder().withName("text")
                            .withDataType(DataType.VarChar).withMaxLength(8192).build())
                    .addFieldType(FieldType.newBuilder().withName("embedding")
                            .withDataType(DataType.FloatVector).withDimension(dimension).build())
                    .build();
            R<RpcStatus> resp = c.createCollection(param);
            log.info("Created Milvus collection {}: status={}", name, resp == null ? "null" : resp.getStatus());
        } catch (Exception e) {
            log.warn("Failed to ensure Milvus collection {}: {}", name, e.getMessage());
        }
    }

    public void insert(String collection, String projectId, String id, String title,
                       String module, String text, List<Float> vector) {
        MilvusServiceClient c = client();
        if (c == null || vector == null || vector.isEmpty()) {
            return;
        }
        try {
            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collection)
                    .withFields(List.of(
                            new InsertParam.Field("id", List.of(id)),
                            new InsertParam.Field("project_id", List.of(projectId == null ? "" : projectId)),
                            new InsertParam.Field("title", List.of(title == null ? "" : title)),
                            new InsertParam.Field("module", List.of(module == null ? "" : module)),
                            new InsertParam.Field("text", List.of(text == null ? "" : text)),
                            new InsertParam.Field("embedding", List.of(vector))))
                    .build();
            c.insert(param);
        } catch (Exception e) {
            log.warn("Milvus insert failed: {}", e.getMessage());
        }
    }

    public List<SearchHit> search(String collection, String projectId, List<Float> vector, int topK) {
        MilvusServiceClient c = client();
        if (c == null || vector == null || vector.isEmpty()) {
            return List.of();
        }
        try {
            List<String> outFields = List.of("id", "title", "module", "text");
            String expr = projectId == null || projectId.isBlank()
                    ? "" : "project_id == \"" + projectId + "\"";
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(outFields)
                    .withTopK(topK)
                    .withVectors(List.of(vector))
                    .withExpr(expr)
                    .build();
            R<SearchResults> resp = c.search(param);
            if (resp == null || resp.getData() == null) {
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchHit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : wrapper.getIDScore(0)) {
                hits.add(new SearchHit(score.getStrID(),
                        field(score, "title"),
                        field(score, "text"),
                        score.getScore()));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteByProject(String collection, String projectId) {
        MilvusServiceClient c = client();
        if (c == null || projectId == null || projectId.isBlank()) {
            return;
        }
        try {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("project_id == \"" + projectId + "\"")
                    .build();
            c.delete(param);
        } catch (Exception e) {
            log.warn("Milvus delete failed: {}", e.getMessage());
        }
    }

    // v5.6: 按 ID 列表删除（用于用例删除/编辑重建）
    public void deleteByIds(String collection, String projectId, List<String> ids) {
        MilvusServiceClient c = client();
        if (c == null || ids == null || ids.isEmpty() || projectId == null || projectId.isBlank()) {
            return;
        }
        try {
            String idExpr = ids.stream()
                    .map(id -> "\"" + id.replace("\"", "\\\"") + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("project_id == \"" + projectId + "\" and id in " + idExpr)
                    .build();
            c.delete(param);
        } catch (Exception e) {
            log.warn("Milvus deleteByIds failed: {}", e.getMessage());
        }
    }

    // v5.6: 按模块删除（PRD/分析上下文替换）
    public void deleteByModule(String collection, String projectId, String module) {
        MilvusServiceClient c = client();
        if (c == null || projectId == null || projectId.isBlank() || module == null || module.isBlank()) {
            return;
        }
        try {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("project_id == \"" + projectId + "\" and module == \"" + module + "\"")
                    .build();
            c.delete(param);
        } catch (Exception e) {
            log.warn("Milvus deleteByModule failed: {}", e.getMessage());
        }
    }

    private String field(SearchResultsWrapper.IDScore score, String name) {
        try {
            Object v = score.get(name);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    public record SearchHit(String id, String title, String text, double score) {
    }
}

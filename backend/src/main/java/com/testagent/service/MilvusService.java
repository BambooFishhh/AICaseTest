package com.testagent.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
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
    // v6.1 (前端 Agentic RAG): 逐组件语义索引集合
    public static final String COLLECTION_COMPONENTS = "components";

    @Value("${app.milvus.enabled:false}")
    private boolean enabled;

    @Value("${app.milvus.host:localhost}")
    private String host;

    @Value("${app.milvus.port:19530}")
    private int port;

    @Value("${app.milvus.dimension:1024}")
    private int dimension;

    @Value("${app.milvus.duplicate-threshold:0.92}")
    private double duplicateThreshold;

    @Value("${app.milvus.username:}")
    private String username;

    @Value("${app.milvus.password:}")
    private String password;

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
                        ConnectParam.Builder connectBuilder =
                                ConnectParam.newBuilder().withHost(host).withPort(port);
                        if (username != null && !username.isBlank()
                                && password != null && !password.isBlank()) {
                            connectBuilder.withAuthorization(username, password);
                        }
                        c = new MilvusServiceClient(connectBuilder.build());
                        ensureCollection(c, COLLECTION_CASES);
                        ensureCollection(c, COLLECTION_CONTEXTS);
                        ensureCollection(c, COLLECTION_FAILURES);
                        ensureCollection(c, COLLECTION_COMPONENTS);
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
            boolean needCreate = exists == null || !Boolean.TRUE.equals(exists.getData());
            if (!needCreate && existingDimension(c, name) != dimension) {
                log.warn("Milvus collection {} dimension mismatch, drop and recreate: expected={}, found={}",
                        name, dimension, existingDimension(c, name));
                c.dropCollection(DropCollectionParam.newBuilder().withCollectionName(name).build());
                needCreate = true;
            }
            if (needCreate) {
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
            }
            // v5.7fix: 已有集合也补建 ANN 索引并加载
            createIndexAndLoad(c, name);
        } catch (Exception e) {
            log.warn("Failed to ensure Milvus collection {}: {}", name, e.getMessage());
        }
    }

    private int existingDimension(MilvusServiceClient c, String name) {
        try {
            R<DescribeCollectionResponse> desc = c.describeCollection(
                    DescribeCollectionParam.newBuilder().withCollectionName(name).build());
            if (desc == null || desc.getData() == null || desc.getData().getSchema() == null) {
                return -1;
            }
            CollectionSchema schema = desc.getData().getSchema();
            for (FieldSchema field : schema.getFieldsList()) {
                if ("embedding".equals(field.getName())) {
                    for (KeyValuePair kv : field.getTypeParamsList()) {
                        if ("dim".equals(kv.getKey())) {
                            return Integer.parseInt(kv.getValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to describe Milvus collection {}: {}", name, e.getMessage());
        }
        return -1;
    }

    private void createIndexAndLoad(MilvusServiceClient c, String name) {
        try {
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(name)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\":128}")
                    .build();
            c.createIndex(indexParam);
            c.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(name).build());
        } catch (Exception ie) {
            log.warn("Failed to create/load Milvus index for {}: {}", name, ie.getMessage());
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
        return search(collection, projectId, vector, topK, null);
    }

    // v6.4: 支持按 module 集合过滤，生成侧 RAG 只召回需求类上下文
    public List<SearchHit> search(String collection, String projectId, List<Float> vector, int topK,
                                  List<String> modules) {
        MilvusServiceClient c = client();
        if (c == null || vector == null || vector.isEmpty()) {
            return List.of();
        }
        try {
            List<String> outFields = List.of("id", "title", "module", "text");
            String expr = buildSearchExpr(projectId, modules);
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectorFieldName("embedding")
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
                        field(score, "module"),
                        field(score, "text"),
                        score.getScore()));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildSearchExpr(String projectId, List<String> modules) {
        StringBuilder sb = new StringBuilder();
        if (projectId != null && !projectId.isBlank()) {
            sb.append("project_id == \"").append(projectId).append("\"");
        }
        if (modules != null && !modules.isEmpty()) {
            List<String> quoted = modules.stream().map(m -> "\"" + m + "\"").toList();
            if (sb.length() > 0) {
                sb.append(" and ");
            }
            sb.append("module in [").append(String.join(",", quoted)).append("]");
        }
        return sb.toString();
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

    // v5.8: 集合行数统计（失败返回 -1）
    public long countCollection(String collection) {
        MilvusServiceClient c = client();
        if (c == null) {
            return -1;
        }
        try {
            R<GetCollectionStatisticsResponse> resp = c.getCollectionStatistics(
                    GetCollectionStatisticsParam.newBuilder().withCollectionName(collection).build());
            if (resp == null || resp.getData() == null) {
                return -1;
            }
            for (KeyValuePair kv : resp.getData().getStatsList()) {
                if ("row_count".equals(kv.getKey())) {
                    return Long.parseLong(kv.getValue());
                }
            }
            return -1;
        } catch (Exception e) {
            log.warn("Milvus count failed for {}: {}", collection, e.getMessage());
            return -1;
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

    public record SearchHit(String id, String title, String module, String text, double score) {
    }
}

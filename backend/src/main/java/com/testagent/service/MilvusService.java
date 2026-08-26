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
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.testagent.entity.PendingVectorOp;
import com.testagent.repository.PendingVectorOpRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    // v8.6.1(9.1): 删除补偿落表仓库——字段默认 null（直 new 单测不受影响），容器注入后生效
    private PendingVectorOpRepository pendingVectorOpRepository;

    @Autowired(required = false)
    void setPendingVectorOpRepository(PendingVectorOpRepository pendingVectorOpRepository) {
        this.pendingVectorOpRepository = pendingVectorOpRepository;
    }

    // v8.7.1(9.5.2): 指标门面——no-op 兜底
    private com.testagent.observability.MetricsFacade metrics = new com.testagent.observability.MetricsFacade();

    @Autowired(required = false)
    void setMetrics(com.testagent.observability.MetricsFacade metrics) {
        this.metrics = metrics;
    }

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        // v8.7.1: 启动零值预注册
        metrics.registerCounter("milvus_insert_truncated_total", "collection", "cases");
        for (String op : new String[]{"insert", "delete", "search", "count", "query"}) {
            metrics.registerCounter("milvus_op_failed_total", "op", op, "collection", "cases");
        }
    }

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
        // v8.7.1(9.5.4): 索引入口注入 projectId MDC，日志链可按项目聚合
        com.testagent.observability.ObservabilityMdc.putProjectId(projectId);
        try {
            doInsert(collection, projectId, id, title, module, text, vector);
        } finally {
            com.testagent.observability.ObservabilityMdc.clear();
        }
    }

    private void doInsert(String collection, String projectId, String id, String title,
                          String module, String text, List<Float> vector) {
        MilvusServiceClient c = client();
        if (c == null || vector == null || vector.isEmpty()) {
            return;
        }
        // v8.4fix: 按 schema VarChar 上限（UTF-8 字节）截断后再写入——Milvus 2.3+ 的
        // max_length 按字节计，中文超限会插入失败；旧逻辑失败仅 warn，向量静默丢失
        // （该用例无法被检索/去重）且无业务侧告警。截断时计数告警便于观测。
        String safeId = truncateToBytes(id, 128);
        String safeProject = truncateToBytes(projectId, 64);
        String safeTitle = truncateToBytes(title, 512);
        String safeModule = truncateToBytes(module, 128);
        String safeText = truncateToBytes(text, 8192);
        if ((title != null && !safeTitle.equals(title)) || (text != null && !safeText.equals(text))) {
            log.warn("Milvus insert 字段截断 (collection={}, id={}, titleLen={}, textLen={})",
                    collection, safeId, title == null ? 0 : title.length(), text == null ? 0 : text.length());
            // v8.7.1(9.5.2): 截断进指标——中文超限静默丢向量的劣化信号
            metrics.increment("milvus_insert_truncated_total", "collection", collection);
        }
        try {
            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collection)
                    .withFields(List.of(
                            new InsertParam.Field("id", List.of(safeId)),
                            new InsertParam.Field("project_id", List.of(safeProject)),
                            new InsertParam.Field("title", List.of(safeTitle)),
                            new InsertParam.Field("module", List.of(safeModule)),
                            new InsertParam.Field("text", List.of(safeText)),
                            new InsertParam.Field("embedding", List.of(vector))))
                    .build();
            c.insert(param);
        } catch (Exception e) {
            log.warn("Milvus insert failed (collection={}, id={}): {}", collection, safeId, e.getMessage());
            // v8.7.1(9.5.2): 操作失败进指标
            metrics.increment("milvus_op_failed_total", "op", "insert", "collection", collection);
        }
    }

    // v8.4fix: 按 UTF-8 字节数截断并回退到合法字符边界，避免截出半个多字节字符

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
            // v8.7.1(9.5.2): 操作失败进指标
            metrics.increment("milvus_op_failed_total", "op", "search", "collection", collection);
            return List.of();
        }
    }

    private String buildSearchExpr(String projectId, List<String> modules) {
        StringBuilder sb = new StringBuilder();
        if (projectId != null && !projectId.isBlank()) {
            sb.append("project_id == \"").append(escapeExpr(projectId)).append("\"");
        }
        if (modules != null && !modules.isEmpty()) {
            // v8.4fix: module 来自分析器/LLM 产出，可能含双引号等特殊字符，拼接前必须转义，
            // 否则 expr 语法错误导致整次检索失败（静默降级为空召回）
            List<String> quoted = modules.stream()
                    .map(m -> "\"" + escapeExpr(m) + "\"").toList();
            if (sb.length() > 0) {
                sb.append(" and ");
            }
            sb.append("module in [").append(String.join(",", quoted)).append("]");
        }
        return sb.toString();
    }

    // v8.4fix: Milvus 布尔表达式字符串转义（反斜杠 + 双引号）——防 expr 注入/语法错误，
    // 所有 withExpr 拼接字符串字段必须经过此方法；deleteByIds 已有行内转义也统一收敛到这里

    public void deleteByProject(String collection, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        deleteWithRetry(collection, "project_id == \"" + escapeExpr(projectId) + "\"");
    }

    // v5.6: 按 ID 列表删除（用于用例删除/编辑重建）
    public void deleteByIds(String collection, String projectId, List<String> ids) {
        MilvusServiceClient c = client();
        if (c == null || ids == null || ids.isEmpty() || projectId == null || projectId.isBlank()) {
            return;
        }
        String idExpr = ids.stream()
                    .map(id -> "\"" + escapeExpr(id) + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        deleteWithRetry(collection,
                "project_id == \"" + escapeExpr(projectId) + "\" and id in " + idExpr);
    }

    // v5.6: 按模块删除（PRD/分析上下文替换）
    public void deleteByModule(String collection, String projectId, String module) {
        if (projectId == null || projectId.isBlank() || module == null || module.isBlank()) {
            return;
        }
        deleteWithRetry(collection,
                "project_id == \"" + escapeExpr(projectId) + "\" and module == \"" + escapeExpr(module) + "\"");
    }

    // v8.6.1(9.2): 补偿重放专用入口——expr 由补偿表原样恢复，重放走统一 deleteWithRetry 链路
    public void deleteByRawExpr(String collection, String expr) {
        if (collection == null || collection.isBlank() || expr == null || expr.isBlank()) {
            return;
        }
        deleteWithRetry(collection, expr);
    }

    // v8.4fix: 删除统一入口：短重试 + 失败升级为 ERROR 并带上下文。
    // 删除失败仅 warn 无补偿会产生召回脏数据（幽灵用例/误判重复），
    // 重试可消除瞬时故障；最终失败升级为 ERROR 便于告警接入与人工对账
    private void deleteWithRetry(String collection, String expr) {
        MilvusServiceClient c = client();
        if (c == null) {
            return;
        }
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                DeleteParam param = DeleteParam.newBuilder()
                        .withCollectionName(collection)
                        .withExpr(expr)
                        .build();
                c.delete(param);
                return;
            } catch (Exception e) {
                last = e;
                log.warn("Milvus delete 失败 (collection={}, attempt={}): {}", collection, attempt, e.getMessage());
            // v8.7.1(9.5.2): 删除失败进指标（终败另有补偿表 + ERROR 日志）
            metrics.increment("milvus_op_failed_total", "op", "delete", "collection", collection);
            }
        }
        log.error("Milvus delete 重试后仍失败，落补偿表待重放 (collection={}, expr={}): {}",
                collection, expr, last == null ? "unknown" : last.getMessage());
        // v8.6.1(9.1): 终败落补偿表（upsert，同 collection+expr 不堆行），由重放任务接管
        recordDeleteFailure(collection, expr, last == null ? "unknown" : last.getMessage());
    }

    // v8.6.1(9.1): 包级私有供单测直测；repo 未注入（纯单测环境）时仅保留日志行为
    void recordDeleteFailure(String collection, String expr, String error) {
        if (pendingVectorOpRepository == null) {
            return;
        }
        try {
            PendingVectorOp existing = pendingVectorOpRepository
                    .findByCollectionAndExprAndStatus(collection, expr, PendingVectorOp.STATUS_PENDING)
                    .orElse(null);
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                PendingVectorOp op = new PendingVectorOp();
                op.setId(UUID.randomUUID().toString().replace("-", ""));
                op.setOpType(PendingVectorOp.OP_DELETE);
                op.setCollection(collection);
                op.setExpr(expr);
                op.setAttempts(0);
                op.setLastError(error);
                op.setStatus(PendingVectorOp.STATUS_PENDING);
                op.setNextAttemptAt(now);
                op.setCreatedAt(now);
                op.setUpdatedAt(now);
                pendingVectorOpRepository.save(op);
            } else {
                existing.setLastError(error);
                existing.setUpdatedAt(now);
                pendingVectorOpRepository.save(existing);
            }
        } catch (Exception recordError) {
            log.error("补偿记录落表失败 (collection={}): {}", collection, recordError.getMessage());
        }
    }

    // v8.6.1(9.3): 按项目查询 cases 集合全部向量 id——对账用。
    // 返回 null 表示查询失败/不可用（区别于合法空集），调用方据此记 SKIPPED 而非误判全量缺失
    public List<String> queryIdsByProject(String collection, String projectId) {
        MilvusServiceClient c = client();
        if (c == null || projectId == null || projectId.isBlank()) {
            return null;
        }
        String expr = "project_id == \"" + escapeExpr(projectId) + "\"";
        List<String> ids = queryIdsPaged(c, collection, expr);
        if (ids == null) {
            metrics.increment("milvus_op_failed_total", "op", "query", "collection", collection);
        }
        return ids;
    }

    // v8.9.2(12.5): 分页循环（每页 1000，返回少于页大小即止）——避免万级项目一次性拉全量 id 的
    // gRPC 报文压力；任一页失败整体返回 null（保留"失败≠空集"语义）。包级私有供单测覆写页获取。
    List<String> queryIdsPaged(MilvusServiceClient c, String collection, String expr) {
        int pageSize = 1000;
        long offset = 0;
        List<String> ids = new ArrayList<>();
        while (true) {
            List<String> page = queryIdPage(c, collection, expr, offset, pageSize);
            if (page == null) {
                return null;
            }
            ids.addAll(page);
            if (page.size() < pageSize) {
                return ids;
            }
            offset += pageSize;
        }
    }

    // 包级私有：单页拉取；失败返回 null
    List<String> queryIdPage(MilvusServiceClient c, String collection, String expr, long offset, int limit) {
        try {
            var resp = c.query(QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr)
                    .withOutFields(List.of("id"))
                    .withLimit((long) limit)
                    .withOffset(offset)
                    .build());
            if (resp == null || resp.getData() == null) {
                return null;
            }
            QueryResultsWrapper wrapper = new QueryResultsWrapper(resp.getData());
            List<String> ids = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                Object id = record.getFieldValues().get("id");
                if (id != null) {
                    ids.add(String.valueOf(id));
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("Milvus queryIdPage failed (collection={}, offset={}): {}", collection, offset, e.getMessage());
            return null;
        }
    }

    // v8.4fix: 布尔表达式字符串转义（反斜杠/双引号）
    private static String escapeExpr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncateToBytes(String value, int maxBytes) {
        if (value == null) {
            return "";
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
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
            metrics.increment("milvus_op_failed_total", "op", "count", "collection", collection);
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

package com.testagent;

import com.testagent.dto.EvidenceConflict;
import com.testagent.entity.EvidenceConflictRecord;
import com.testagent.entity.Project;
import com.testagent.repository.EvidenceConflictRecordRepository;
import com.testagent.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT7: MySQL 方言集成测试——Flyway 迁移 + JPA 基本读写。
 * Docker 不可用时自动跳过。
 *
 * <p>v13.17/v13.18 起，本类同时充当 **V17/V18 迁移在真实 MySQL 上的唯一安全网**：
 * H2({@code MODE=MySQL}) 能跑通 DDL 语法，但下列方言差异只有真实 MySQL 才暴露——
 * <ul>
 *   <li>{@code verdict} 的 DB 级 {@code DEFAULT 'pending'}：H2 下该表由 Hibernate 按实体生成，**没有**该默认值</li>
 *   <li>{@code TINYINT(1)} 与 JPA {@code Boolean} 的映射（H2 下是 {@code BOOLEAN}）</li>
 *   <li>{@code DATETIME(6)} 与 {@code LocalDateTime} 的映射</li>
 *   <li>列宽硬约束：{@code authority VARCHAR(16)} 与 {@code verdict VARCHAR(24)} 不可混用</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.mcp.enabled=false",
        "app.milvus.enabled=false",
        "app.redis.enabled=false",
        // v8.5: SecurityKeyGuard 全 profile 必填，测试上下文显式补键
        "app.jwt.secret=integration-test-jwt-secret-0123456789abcdef",
        "app.admin.password=integration-test-admin-pw",
        "app.milvus.password=integration-test-milvus-pw",
        "app.mcp.bridge-token=integration-test-mcp-token",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/mysql",
        "spring.flyway.baseline-on-migrate=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none"
})
class MySqlFlywayIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.29")
            .withDatabaseName("aicasetest")
            .withUsername("aicasetest")
            .withPassword("aicasetest123")
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EvidenceConflictRecordRepository conflictRepository;

    @Test
    void flywayMigrationsAreApplied() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertTrue(count != null && count >= 2, "V1/V2 migrations should be applied");
    }

    @Test
    void jpaCanReadWriteOnMySql() {
        Project project = new Project();
        project.setId("p-it");
        project.setName("integration");
        project.setSourceType("none");
        projectRepository.save(project);

        assertEquals("integration", projectRepository.findById("p-it").orElseThrow().getName());
        projectRepository.delete(project);
    }

    // ---------- V17: evidence_conflicts ----------

    @Test
    void v17EvidenceConflictsTableIsCreated() {
        assertEquals(1, tableCount("evidence_conflicts"), "V17 应创建 evidence_conflicts 表");

        // 列宽是硬约束：authority 只存短值(prd/code/human/deprecated)，verdict 需容纳 18 字符的 code_authoritative
        assertEquals("varchar(16)", columnType("evidence_conflicts", "authority"));
        assertEquals("varchar(24)", columnType("evidence_conflicts", "verdict"));
        assertEquals("varchar(512)", columnType("evidence_conflicts", "anchor"));
        assertEquals("varchar(32)", columnType("evidence_conflicts", "conflict_key"));
        assertEquals("varchar(32)", columnType("evidence_conflicts", "dimension"));

        // verdict 的 DB 级默认值——H2 下不存在，是真实方言的关键差异点
        assertEquals("NO", columnNullable("evidence_conflicts", "verdict"));
        String verdictDefault = normalizeDefault(columnDefault("evidence_conflicts", "verdict"));
        assertNotNull(verdictDefault, "verdict 应带 DB 级默认值");
        assertTrue(verdictDefault.contains("pending"), "verdict 默认值应为 pending，实际=" + verdictDefault);

        // TINYINT(1) 供 JPA Boolean 映射
        assertEquals("tinyint(1)", columnType("evidence_conflicts", "stale"));
        assertEquals("tinyint(1)", columnType("evidence_conflicts", "manual_required"));

        assertEquals(1, indexCount("evidence_conflicts", "idx_evidence_conflicts_project"));
        assertEquals(1, indexCount("evidence_conflicts", "idx_evidence_conflicts_pending"));
    }

    @Test
    void evidenceConflictRecordRoundTripsOnMySql() {
        String projectId = "p-v17-it";
        // 用全 0 占位：十进制样式的 hash 串会被 gitleaks 的 generic-api-key 规则
        // 误判（变量名含 Key + 十六进制外观），CI secret scan 会因此失败
        String conflictKey = "ec-0000000000";
        String id = EvidenceConflictRecord.buildId(projectId, conflictKey);

        EvidenceConflictRecord record = new EvidenceConflictRecord();
        record.setId(id);
        record.setProjectId(projectId);
        record.setConflictKey(conflictKey);
        record.setDimension(EvidenceConflict.DIM_STATE_FLOW);
        record.setAnchor("下单流程");                    // 中文锚点，顺带验证 utf8mb4
        record.setDirection("PRD_ONLY");
        record.setPrdSide("[\"待支付\"]");
        record.setCodeSide("[]");
        record.setRequirementState("NEW");
        record.setStale(Boolean.FALSE);
        record.setAuthority("prd");
        record.setManualRequired(Boolean.FALSE);
        record.setReason("新需求：以 PRD 为准");
        record.setCreatedAt(LocalDateTime.now());
        conflictRepository.save(record);

        // 无 @Transactional，save 即落库，findById 是真实的 DB 往返（非一级缓存）
        EvidenceConflictRecord loaded = conflictRepository.findById(id).orElseThrow();
        assertEquals("下单流程", loaded.getAnchor());
        assertEquals(EvidenceConflict.DIM_STATE_FLOW, loaded.getDimension());
        assertEquals(Boolean.FALSE, loaded.getStale(), "TINYINT(1) ↔ Boolean 映射");
        assertEquals(Boolean.FALSE, loaded.getManualRequired(), "TINYINT(1) ↔ Boolean 映射");
        assertEquals(EvidenceConflictRecord.VERDICT_PENDING, loaded.getVerdict(), "未显式赋值应落 pending");
        assertEquals(1, conflictRepository
                .findByProjectIdAndVerdictOrderByCreatedAtDesc(projectId, EvidenceConflictRecord.VERDICT_PENDING)
                .size());

        // 裁决值 prd_authoritative 为 17 字符 —— 验证 verdict 列宽(24) 足够，且 authority 列(16) 确实不能存它
        loaded.setVerdict(EvidenceConflictRecord.VERDICT_PRD);
        loaded.setVerdictBy("admin");
        loaded.setVerdictAt(LocalDateTime.now());
        conflictRepository.save(loaded);

        EvidenceConflictRecord resolved = conflictRepository.findById(id).orElseThrow();
        assertEquals(EvidenceConflictRecord.VERDICT_PRD, resolved.getVerdict());
        assertEquals("admin", resolved.getVerdictBy(), "作者列宽 64 足以存操作人");
        assertNotNull(resolved.getVerdictAt(), "DATETIME(6) ↔ LocalDateTime 映射");
        assertTrue(resolved.isResolved(), "非 pending 即视为已裁决");
        assertEquals("prd", resolved.resolvedAuthority());
        assertEquals(1L, conflictRepository.countByProjectIdAndVerdict(projectId, EvidenceConflictRecord.VERDICT_PRD));

        jdbcTemplate.update("DELETE FROM evidence_conflicts WHERE project_id = ?", projectId);
        assertTrue(conflictRepository.findByProjectIdOrderByCreatedAtDesc(projectId).isEmpty());
    }

    // ---------- V18: test_cases 待裁决溯源列 ----------

    @Test
    void v18TestCaseVerdictColumnsAreCreated() {
        // dimension 在 MySQL 中非保留字，可作列名
        assertEquals("varchar(24)", columnType("test_cases", "verdict"));
        assertEquals("varchar(512)", columnType("test_cases", "conflict_ref"));
        assertEquals("varchar(64)", columnType("test_cases", "dimension"));

        // 存量行不带标记：三列必须可空，否则 ALTER 在老数据上会直接失败
        assertEquals("YES", columnNullable("test_cases", "verdict"), "存量用例该列为 NULL");
        assertEquals("YES", columnNullable("test_cases", "conflict_ref"));
        assertEquals("YES", columnNullable("test_cases", "dimension"));

        assertEquals(1, indexCount("test_cases", "idx_test_cases_verdict"));
    }

    // ---------- helpers ----------

    private Integer tableCount(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT COLUMN_TYPE FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private String columnNullable(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private String columnDefault(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private Integer indexCount(String table, String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, indexName);
    }

    /** MySQL 对字符串默认值的回显可能带引号（取决于版本），统一剥掉再比较 */
    private static String normalizeDefault(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        while (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}


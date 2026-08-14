package com.testagent.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v5.1: H2 → MySQL 全量迁移工具。
 * 通过 migrate profile + APP_MIGRATION_ENABLED=true 触发，默认不执行。
 * 流程：H2 文件备份 → 逐表 JDBC 复制 → 行数校验 → 摘要文件；失败时清理已写入的 MySQL 表。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class H2ToMysqlMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(H2ToMysqlMigrator.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final List<String> TABLES = List.of(
            "users", "project_groups", "group_members", "projects", "test_cases",
            "test_case_versions", "state_machines", "code_analysis", "mindmaps",
            "test_suites", "execution_record", "execution_step", "system_settings"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.migration.enabled:false}")
    private boolean migrationEnabled;

    @Value("${app.migration.rollback:false}")
    private boolean rollback;

    @Value("${app.migration.backup-dir:backups}")
    private String backupDir;

    @Value("${app.migration.source-url:jdbc:h2:file:./data/appdb;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE}")
    private String sourceUrl;

    @Value("${app.migration.source-user:sa}")
    private String sourceUser;

    @Value("${app.migration.source-password:}")
    private String sourcePassword;

    @Autowired
    private DataSource targetDataSource;

    @Override
    public void run(String... args) throws Exception {
        if (!migrationEnabled) {
            return;
        }
        if (rollback) {
            restoreBackup();
            return;
        }
        backupH2Files();
        try (Connection src = DriverManager.getConnection(sourceUrl, sourceUser, sourcePassword);
             Connection dst = targetDataSource.getConnection()) {
            List<String> migrated = new ArrayList<>();
            try {
                for (String table : TABLES) {
                    if (migrateTable(src, dst, table)) {
                        migrated.add(table);
                    }
                }
                verifyCounts(src, dst);
                writeSummary(dst, migrated);
                log.info("H2 -> MySQL migration completed, tables={}", migrated);
            } catch (Exception e) {
                log.error("H2 -> MySQL migration failed, cleaning target tables: {}", migrated, e);
                cleanupTarget(dst, migrated);
                throw e;
            }
        }
    }

    private boolean migrateTable(Connection src, Connection dst, String table) throws Exception {
        if (!tableExists(src, table)) {
            log.info("Skip table {}: not found in H2", table);
            return false;
        }
        String select = "SELECT * FROM " + table;
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(select)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>();
            StringBuilder colSql = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            StringBuilder updateSql = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                String col = md.getColumnName(i).toLowerCase(Locale.ROOT);
                cols.add(col);
                if (i > 1) {
                    colSql.append(", ");
                    placeholders.append(", ");
                    updateSql.append(", ");
                }
                colSql.append('`').append(col).append('`');
                placeholders.append('?');
                updateSql.append('`').append(col).append("`=VALUES(`").append(col).append("`)");
            }
            String insert = "INSERT INTO `" + table + "` (" + colSql + ") VALUES (" + placeholders + ")"
                    + " ON DUPLICATE KEY UPDATE " + updateSql;
            int count = 0;
            try (PreparedStatement ps = dst.prepareStatement(insert)) {
                while (rs.next()) {
                    for (int i = 1; i <= n; i++) {
                        ps.setObject(i, rs.getObject(i));
                    }
                    ps.addBatch();
                    if (++count % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            log.info("Migrated {} rows from H2 to MySQL table {}", count, table);
            return true;
        }
    }

    private boolean tableExists(Connection src, String table) {
        try (ResultSet rs = src.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to inspect H2 tables, fallback to query probe: {}", e.getMessage());
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private void verifyCounts(Connection src, Connection dst) throws Exception {
        Map<String, Long> mismatches = new LinkedHashMap<>();
        for (String table : TABLES) {
            if (!tableExists(src, table)) {
                continue;
            }
            long srcCount = count(src, table);
            long dstCount = count(dst, "`" + table + "`");
            log.info("Verify table {}: H2={}, MySQL={}", table, srcCount, dstCount);
            if (dstCount < srcCount) {
                mismatches.put(table, srcCount - dstCount);
            }
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("迁移校验失败，存在行数不足的表: " + mismatches);
        }
    }

    private long count(Connection conn, String quotedOrRawTable) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quotedOrRawTable)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void cleanupTarget(Connection dst, List<String> migrated) throws Exception {
        for (int i = migrated.size() - 1; i >= 0; i--) {
            String table = migrated.get(i);
            try (Statement st = dst.createStatement()) {
                st.executeUpdate("DELETE FROM `" + table + "`");
                log.info("Cleaned MySQL table {} after failed migration", table);
            } catch (Exception e) {
                log.warn("Failed to clean MySQL table {}: {}", table, e.getMessage());
            }
        }
    }

    private void backupH2Files() throws Exception {
        String stamp = LocalDateTime.now().format(TS);
        Path backup = Path.of(backupDir, "h2-backup-" + stamp);
        Files.createDirectories(backup);
        Path dataDir = Path.of("data");
        boolean any = false;
        for (String suffix : new String[]{".mv.db", ".trace.db", ".lock.db"}) {
            Path src = dataDir.resolve("appdb" + suffix);
            if (Files.exists(src)) {
                Files.copy(src, backup.resolve(src.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                any = true;
            }
        }
        if (!any) {
            log.warn("No H2 db files found under data/, backup dir created anyway: {}", backup);
        } else {
            log.info("H2 files backed up to {}", backup);
        }
    }

    private void restoreBackup() throws Exception {
        Path root = Path.of(backupDir);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("备份目录不存在: " + backupDir);
        }
        Path latest = null;
        try (var stream = Files.list(root)) {
            for (Path p : stream.filter(Files::isDirectory).sorted().toList()) {
                latest = p;
            }
        }
        if (latest == null) {
            throw new IllegalStateException("没有可用备份目录");
        }
        Path dataDir = Path.of("data");
        Files.createDirectories(dataDir);
        try (var stream = Files.list(latest)) {
            for (Path p : stream.toList()) {
                Files.copy(p, dataDir.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                log.info("Restored {} -> {}", p, dataDir.resolve(p.getFileName().toString()));
            }
        }
        log.info("H2 rollback completed from {}", latest);
    }

    private void writeSummary(Connection dst, List<String> migrated) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timestamp", LocalDateTime.now().toString());
        summary.put("result", "success");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            try {
                counts.put(table, count(dst, "`" + table + "`"));
            } catch (Exception e) {
                counts.put(table, -1L);
            }
        }
        summary.put("tables", counts);
        summary.put("migrated", migrated);
        String stamp = LocalDateTime.now().format(TS);
        Path out = Path.of(backupDir, "migration-" + stamp + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        log.info("Migration summary written to {}", out);
    }
}

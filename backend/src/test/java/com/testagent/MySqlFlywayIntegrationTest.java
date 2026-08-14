package com.testagent;

import com.testagent.entity.Project;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT7: MySQL 方言集成测试——Flyway V1/V2 迁移 + JPA 基本读写。
 * Docker 不可用时自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.mcp.enabled=false",
        "app.milvus.enabled=false",
        "app.redis.enabled=false",
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
}


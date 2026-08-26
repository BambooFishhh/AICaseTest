package com.testagent.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v8.6.1(9.2): ShedLock 分布式调度锁——补偿重放/周期对账任务多实例互斥。
 * 锁表与业务库同库（MySQL 由 Flyway V15 建表；H2 开发/测试不走 Flyway，
 * 启动时幂等 CREATE TABLE IF NOT EXISTS 兜底）。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build());
    }

    // H2 环境（flyway.enabled=false）启动兜底建表；MySQL 上 IF NOT EXISTS 无副作用
    @Bean
    public ApplicationRunner shedlockTableBootstrap(JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS shedlock ("
                        + "name VARCHAR(64) NOT NULL, "
                        + "lock_until TIMESTAMP(3) NOT NULL, "
                        + "locked_at TIMESTAMP(3) NOT NULL, "
                        + "locked_by VARCHAR(255) NOT NULL, "
                        + "PRIMARY KEY (name))");
            } catch (Exception e) {
                // 表已存在等场景不阻断启动（ShedLock 运行期仍会校验）
            }
        };
    }
}

package com.myai.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 数据库初始化器
 * 启动时自动执行 update.sql
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${spring.datasource.url:jdbc:sqlite:data/gateway.db}")
    private String dbUrl;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) throws Exception {
        // 检查是否需要初始化
        try {
            // 读取 update.sql
            ClassPathResource resource = new ClassPathResource("update.sql");
            if (!resource.exists()) {
                return;
            }

            String sqlContent;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                sqlContent = reader.lines().collect(Collectors.joining("\n"));
            }

            // 按版本分组执行
            String[] versions = sqlContent.split("-- ========================================");
            for (String version : versions) {
                if (version.trim().isEmpty()) {
                    continue;
                }
                executeVersion(version);
            }

            log.info("Database initialized successfully");
        } catch (Exception e) {
            log.warn("Database initialization warning: {}", e.getMessage());
        }
    }

    private void executeVersion(String versionContent) {
        // 提取版本号
        String version = extractVersion(versionContent);
        if (version == null) {
            return;
        }

        // 检查是否已执行
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM db_schema_version WHERE version = ?",
                    Integer.class, version);
            if (count != null && count > 0) {
                return; // 已执行，跳过
            }
        } catch (Exception e) {
            // 表不存在，先创建
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS db_schema_version (" +
                    "version TEXT PRIMARY KEY, applied_at TIMESTAMP, description TEXT)");
        }

        String description = extractDescription(versionContent);
        try {
            // Spring 管理事务，确保版本内 SQL 原子性。
            // 不能用裸 BEGIN/COMMIT：JdbcTemplate 每次调用独立借还池连接，并发借用下
            // BEGIN 与 COMMIT 可能落在不同连接上，事务形同虚设；且 BEGIN 残留的连接
            // 归还池后会与驱动的 autocommit 状态管理冲突，污染池连接
            // （见 SelfHealingHikariDataSource）。
            transactionTemplate.executeWithoutResult(status -> {
                // 执行 SQL 语句
                String[] statements = versionContent.split(";");
                for (String sql : statements) {
                    sql = sql.trim();
                    if (sql.isEmpty() || sql.startsWith("--")) {
                        continue;
                    }
                    try {
                        jdbcTemplate.execute(sql);
                    } catch (Exception e) {
                        // 忽略部分错误（如 ALTER TABLE 如果列已存在）
                        log.warn("SQL warning: {}", e.getMessage());
                    }
                }

                // 记录版本（使用参数化查询，防止 SQL 注入）
                jdbcTemplate.update(
                        "INSERT INTO db_schema_version (version, description) VALUES (?, ?)",
                        version, description);
            });
        } catch (Exception e) {
            log.error("Failed to execute database version {}, rolled back: {}", version, e.getMessage());
        }
    }

    private String extractVersion(String content) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("VERSION:")) {
                return line.substring(8).trim();
            }
        }
        return null;
    }

    private String extractDescription(String content) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("--")) {
                String desc = line.replace("--", "").trim();
                if (!desc.isEmpty()) {
                    return desc.replace("'", "''");
                }
            }
        }
        return "";
    }
}
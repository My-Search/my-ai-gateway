package com.myai.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库迁移运行器
 * 从 update.sql 中读取版本化 SQL 块并按顺序执行
 * 参考 ai-rss-hub 项目的迁移模式
 */
@Component
@Order(Integer.MIN_VALUE)
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private static final String VERSION_MARKER = "-- VERSION:";
    private static final String VERSION_TABLE = "db_schema_version";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== 开始数据库迁移 ===");
        try {
            // 确保数据目录存在
            ensureDataDirectory();
            ensureVersionTable();
            List<MigrationBlock> blocks = parseMigrationFile();
            for (MigrationBlock block : blocks) {
                if (!isVersionApplied(block.version)) {
                    log.info("执行迁移版本: {} - {}", block.version, block.description);
                    executeMigration(block);
                    markVersionApplied(block.version, block.description);
                    log.info("迁移版本 {} 完成", block.version);
                } else {
                    log.debug("迁移版本 {} 已执行，跳过", block.version);
                }
            }
            log.info("=== 数据库迁移完成 ===");
        } catch (Exception e) {
            log.error("数据库迁移失败", e);
            throw new RuntimeException("数据库迁移失败", e);
        }
    }

    /**
     * 确保数据目录存在（SQLite 不会自动创建目录）
     */
    private void ensureDataDirectory() {
        try {
            Files.createDirectories(Paths.get("data"));
        } catch (Exception e) {
            log.warn("创建数据目录失败: {}", e.getMessage());
        }
    }

    private void ensureVersionTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " (" +
                "version TEXT PRIMARY KEY," +
                "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "description TEXT DEFAULT '')");
    }

    private List<MigrationBlock> parseMigrationFile() throws Exception {
        ClassPathResource resource = new ClassPathResource("update.sql");
        String content;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            content = reader.lines().collect(Collectors.joining("\n"));
        }

        List<MigrationBlock> blocks = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder currentSql = new StringBuilder();
        String currentVersion = null;
        String currentDescription = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(VERSION_MARKER)) {
                // 保存上一个版本
                if (currentVersion != null && !currentSql.isEmpty()) {
                    blocks.add(new MigrationBlock(currentVersion, currentDescription, currentSql.toString()));
                }
                // 解析新版本
                String versionInfo = trimmed.substring(VERSION_MARKER.length()).trim();
                currentVersion = versionInfo;
                currentDescription = "";
                currentSql = new StringBuilder();
            } else if (trimmed.startsWith("--") && currentVersion != null) {
                // 版本描述行
                String desc = trimmed.substring(2).trim();
                if (!desc.isEmpty()) {
                    if (currentDescription == null || currentDescription.isEmpty()) {
                        currentDescription = desc;
                    }
                }
            } else if (!trimmed.startsWith("--") && !trimmed.isEmpty()) {
                currentSql.append(line).append("\n");
            }
        }
        // 保存最后一个版本
        if (currentVersion != null && !currentSql.isEmpty()) {
            blocks.add(new MigrationBlock(currentVersion, currentDescription, currentSql.toString()));
        }

        log.info("解析到 {} 个迁移版本", blocks.size());
        return blocks;
    }

    private boolean isVersionApplied(String version) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + VERSION_TABLE + " WHERE version = ?",
                    Integer.class, version);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void executeMigration(MigrationBlock block) {
        // SQLite 不支持多条语句一起执行，需要分条执行
        String[] statements = block.sql.split(";");
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                try {
                    jdbcTemplate.execute(trimmed);
                } catch (Exception e) {
                    // SQLite 的幂等处理：如果表已存在或列已存在，忽略错误
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (msg.contains("already exists") || msg.contains("duplicate column")) {
                        log.debug("幂等忽略: {}", e.getMessage());
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private void markVersionApplied(String version, String description) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO " + VERSION_TABLE + " (version, description) VALUES (?, ?)",
                version, description);
    }

    private record MigrationBlock(String version, String description, String sql) {
    }
}

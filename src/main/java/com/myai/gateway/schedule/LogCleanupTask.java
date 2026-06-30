package com.myai.gateway.schedule;

import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.RequestLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日志清理定时任务
 * <p>
 * 每 30 分钟执行一次，根据系统配置清理过期的请求日志和原始请求数据（request_headers / request_body），
 * 防止 SQLite 数据库文件持续增长占用过多磁盘空间。
 * </p>
 *
 * <pre>
 * 处理流程：
 * 1. 从 admin_config 读取日志清理开关（log_cleanup_enabled）
 * 2. 若开关关闭则跳过本次执行
 * 3. 读取日志保留天数（log_retention_days），调用 cleanOldLogs() 清理过期日志
 * 4. 读取普通原始请求数据保留时长（request_body_ttl_hours），调用 cleanExpiredRequestData() 清理普通记录
 * 5. 读取重试/失败请求数据保留时长（retry_fail_ttl_hours），调用 cleanExpiredRequestData() 清理重试/失败记录
 * 6. 记录本次清理结果
 * </pre>
 */
@Component
public class LogCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(LogCleanupTask.class);

    private final AdminConfigService adminConfigService;
    private final RequestLogService requestLogService;

    public LogCleanupTask(AdminConfigService adminConfigService, RequestLogService requestLogService) {
        this.adminConfigService = adminConfigService;
        this.requestLogService = requestLogService;
    }

    /**
     * 定时清理过期日志与原始请求数据
     * <p>
     * 每 30 分钟执行一次（0 分和 30 分时触发），统一处理两种数据的过期清理：
     * 日志按天、原始请求数据按小时（区分普通记录与重试/失败记录）。
     * cron = "0 0/30 * * * ?" 对应每小时的第 0 分和第 30 分执行。
     * </p>
     */
    @Scheduled(cron = "0 0/30 * * * ?")
    public void cleanExpiredData() {
        // 1. 检查定时清理开关
        String enabled = adminConfigService.getValueByKey(AdminConfigService.KEY_LOG_CLEANUP_ENABLED);
        if (!"1".equals(enabled)) {
            log.debug("定时清理已关闭，跳过本次执行");
            return;
        }

        // 2. 执行过期日志清理
        String daysStr = adminConfigService.getValueByKey(AdminConfigService.KEY_LOG_RETENTION_DAYS);
        int retentionDays;
        try {
            retentionDays = Integer.parseInt(daysStr);
            if (retentionDays <= 0) {
                log.warn("日志保留天数配置无效（{}），使用默认值 7 天", daysStr);
                retentionDays = 7;
            }
        } catch (NumberFormatException e) {
            log.warn("日志保留天数配置格式错误（{}），使用默认值 7 天", daysStr);
            retentionDays = 7;
        }

        if (retentionDays > 0) {
            log.debug("开始清理 {} 天前的过期日志", retentionDays);
            try {
                requestLogService.cleanOldLogs(retentionDays);
            } catch (Exception e) {
                log.error("过期日志清理失败", e);
            }
        }

        // 3. 读取普通原始请求数据保留时长
        String ttlStr = adminConfigService.getValueByKey(AdminConfigService.KEY_REQUEST_BODY_TTL_HOURS);
        int ttlHours;
        try {
            ttlHours = Integer.parseInt(ttlStr);
            if (ttlHours < 0) {
                ttlHours = 0;
            }
        } catch (NumberFormatException e) {
            ttlHours = 0;
        }

        // 4. 读取重试/失败请求数据保留时长
        String retryFailTtlStr = adminConfigService.getValueByKey(AdminConfigService.KEY_RETRY_FAIL_TTL_HOURS);
        int retryFailTtlHours;
        try {
            retryFailTtlHours = Integer.parseInt(retryFailTtlStr);
            if (retryFailTtlHours < 0) {
                retryFailTtlHours = 0;
            }
        } catch (NumberFormatException e) {
            retryFailTtlHours = 0;
        }

        // 5. 清理过期的原始请求数据（同时处理普通记录与重试/失败记录）
        if (ttlHours > 0 || retryFailTtlHours > 0) {
            log.debug("开始清理原始请求数据：普通TTL={}h, 重试/失败TTL={}h", ttlHours, retryFailTtlHours);
            try {
                requestLogService.cleanExpiredRequestData(ttlHours, retryFailTtlHours);
            } catch (Exception e) {
                log.error("原始请求数据清理失败", e);
            }
        }
    }
}

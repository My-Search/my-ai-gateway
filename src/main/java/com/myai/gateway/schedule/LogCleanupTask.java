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
 * 每天凌晨 3:00 执行，根据系统配置的日志保留天数清理过期的请求日志，
 * 防止 SQLite 数据库文件持续增长占用过多磁盘空间。
 * </p>
 *
 * <pre>
 * 处理流程：
 * 1. 从 admin_config 读取日志清理开关（log_cleanup_enabled）
 * 2. 若开关关闭则跳过本次执行
 * 3. 读取日志保留天数（log_retention_days）
 * 4. 调用 RequestLogService.cleanOldLogs() 清理过期日志
 * 5. 记录本次清理结果
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
     * 定时清理过期日志
     * <p>
     * 每天凌晨 3:00（北京时间）执行一次。
     * Spring Cron 表达式按服务器本地时间执行，若服务器为 UTC 时区则为 3:00 UTC，
     * 若为北京时间（UTC+8）则对应实际北京时间的凌晨 3:00。
     * 项目已在 TimeZoneConfig 中固定时区为 Asia/Shanghai（UTC+8），
     * 因此 cron = "0 0 3 * * ?" 对应北京时间每天凌晨 3:00。
     * </p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredLogs() {
        // 1. 检查定时清理开关
        String enabled = adminConfigService.getValueByKey(AdminConfigService.KEY_LOG_CLEANUP_ENABLED);
        if (!"1".equals(enabled)) {
            log.debug("日志定时清理已关闭，跳过本次执行");
            return;
        }

        // 2. 读取日志保留天数配置
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

        // 3. 执行清理
        log.info("开始清理 {} 天前的过期日志", retentionDays);
        try {
            requestLogService.cleanOldLogs(retentionDays);
            log.info("日志清理完成（保留 {} 天）", retentionDays);
        } catch (Exception e) {
            log.error("日志清理失败", e);
        }
    }
}

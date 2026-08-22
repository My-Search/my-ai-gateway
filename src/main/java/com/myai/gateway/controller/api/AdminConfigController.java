package com.myai.gateway.controller.api;

import com.myai.gateway.service.AdminConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台「系统配置 + 文件上传」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载系统级配置的读写（日志保留、原始请求 TTL、超时区间等）与图片上传接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminConfigController {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigController.class);

    /** 允许上传的图片文件扩展名 */
    private static final java.util.Set<String> ALLOWED_IMAGE_EXTENSIONS = java.util.Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg"
    );

    private final AdminConfigService adminConfigService;

    public AdminConfigController(AdminConfigService adminConfigService) {
        this.adminConfigService = adminConfigService;
    }

    /**
     * 获取系统配置
     * <p>
     * 返回所有系统级别配置项，包括日志管理、定时任务等。
     * </p>
     */
    @GetMapping(value = "/config/system", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> getSystemConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", adminConfigService.getSystemConfig());
        return ResponseEntity.ok(result);
    }

    /**
     * 更新系统配置
     * <p>
     * 批量更新系统配置项，仅更新传入的 key。
     * </p>
     * 请求体示例：
     * <pre>
     * {
     *   "log_retention_days": "30",
     *   "log_cleanup_enabled": "1"
     * }
     * </pre>
     */
    @PutMapping(value = "/config/system", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> updateSystemConfig(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 校验参数
            if (body.containsKey(AdminConfigService.KEY_LOG_RETENTION_DAYS)) {
                String days = body.get(AdminConfigService.KEY_LOG_RETENTION_DAYS);
                int val = Integer.parseInt(days);
                if (val < 1 || val > 365) {
                    result.put("success", false);
                    result.put("error", "日志保留天数必须在 1-365 之间");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_LOG_CLEANUP_ENABLED)) {
                String val = body.get(AdminConfigService.KEY_LOG_CLEANUP_ENABLED);
                if (!"0".equals(val) && !"1".equals(val)) {
                    result.put("success", false);
                    result.put("error", "清理开关值无效，必须为 0 或 1");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_REQUEST_BODY_TTL_HOURS)) {
                String val = body.get(AdminConfigService.KEY_REQUEST_BODY_TTL_HOURS);
                try {
                    int hours = Integer.parseInt(val);
                    if (hours < 0 || hours > 8760) {
                        result.put("success", false);
                        result.put("error", "原始请求保留时长必须在 0-8760 小时之间（0=永久保留）");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "原始请求保留时长必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_RETRY_FAIL_TTL_HOURS)) {
                String val = body.get(AdminConfigService.KEY_RETRY_FAIL_TTL_HOURS);
                try {
                    int hours = Integer.parseInt(val);
                    if (hours < 0 || hours > 8760) {
                        result.put("success", false);
                        result.put("error", "重试/失败请求保留时长必须在0-8760小时之间（0=跟随日志保留天数）");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "重试/失败请求保留时长必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_REQUEST_DATA_SAVE_LEVEL)) {
                String val = body.get(AdminConfigService.KEY_REQUEST_DATA_SAVE_LEVEL);
                if (val == null || (!"info".equals(val) && !"warn".equals(val) && !"error".equals(val))) {
                    result.put("success", false);
                    result.put("error", "原始请求数据保存级别无效，必须为 info / warn / error");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_TIMEOUT_MIN_SECONDS)) {
                String val = body.get(AdminConfigService.KEY_TIMEOUT_MIN_SECONDS);
                try {
                    int sec = Integer.parseInt(val);
                    if (sec < 1 || sec > 600) {
                        result.put("success", false);
                        result.put("error", "最小超时时间必须在 1-600 秒之间");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "最小超时时间必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }
            if (body.containsKey(AdminConfigService.KEY_TIMEOUT_MAX_SECONDS)) {
                String val = body.get(AdminConfigService.KEY_TIMEOUT_MAX_SECONDS);
                try {
                    int sec = Integer.parseInt(val);
                    if (sec < 1 || sec > 600) {
                        result.put("success", false);
                        result.put("error", "最大超时时间必须在 1-600 秒之间");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "最大超时时间必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }
            // 当同时更新 min 和 max 时，校验 min ≤ max
            if (body.containsKey(AdminConfigService.KEY_TIMEOUT_MIN_SECONDS)
                    && body.containsKey(AdminConfigService.KEY_TIMEOUT_MAX_SECONDS)) {
                int minVal = Integer.parseInt(body.get(AdminConfigService.KEY_TIMEOUT_MIN_SECONDS));
                int maxVal = Integer.parseInt(body.get(AdminConfigService.KEY_TIMEOUT_MAX_SECONDS));
                if (minVal > maxVal) {
                    result.put("success", false);
                    result.put("error", "最小超时时间不能大于最大超时时间");
                    return ResponseEntity.ok(result);
                }
            }

            if (body.containsKey(AdminConfigService.KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES)) {
                String val = body.get(AdminConfigService.KEY_CHANNEL_MODEL_REFRESH_INTERVAL_MINUTES);
                try {
                    int minutes = Integer.parseInt(val);
                    if (minutes < 1 || minutes > 1440) {
                        result.put("success", false);
                        result.put("error", "渠道模型刷新间隔必须在 1-1440 分钟之间");
                        return ResponseEntity.ok(result);
                    }
                } catch (NumberFormatException e) {
                    result.put("success", false);
                    result.put("error", "渠道模型刷新间隔必须为有效数字");
                    return ResponseEntity.ok(result);
                }
            }

            adminConfigService.updateSystemConfig(body);
            result.put("success", true);
            result.put("data", adminConfigService.getSystemConfig());
        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("error", "日志保留天数必须为有效数字");
        } catch (Exception e) {
            log.warn("更新系统配置失败", e);
            result.put("success", false);
            result.put("error", "更新失败：" + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 上传文件（图片），返回可访问的 URL
     * POST /admin/api/upload
     * 请求：multipart/form-data，字段名 file
     * 返回：{ success: true, url: "/uploads/xxx.jpg", originalName: "xxx.jpg" }
     */
    @PostMapping(value = "/upload", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("success", false);
                result.put("error", "文件为空");
                return ResponseEntity.ok(result);
            }
            // 校验文件类型：只允许图片
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                result.put("success", false);
                result.put("error", "只允许上传图片文件");
                return ResponseEntity.ok(result);
            }
            // 校验文件扩展名白名单
            String originalName = file.getOriginalFilename();
            if (originalName != null) {
                String ext = "";
                int dotIdx = originalName.lastIndexOf(".");
                if (dotIdx >= 0) {
                    ext = originalName.substring(dotIdx).toLowerCase();
                }
                if (!ALLOWED_IMAGE_EXTENSIONS.contains(ext)) {
                    result.put("success", false);
                    result.put("error", "不允许上传该文件类型（仅支持: jpg/png/gif/webp/bmp/svg）");
                    return ResponseEntity.ok(result);
                }
            }
            // 生成存储路径：data/uploads/yyyy/MM/dd/uuid.ext
            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }
            String datePath = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String uuid = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String relativePath = datePath + "/" + uuid + ext;
            java.io.File dest = new java.io.File("data/uploads/" + relativePath);
            dest.getParentFile().mkdirs();
            file.transferTo(dest);
            result.put("success", true);
            result.put("url", "/uploads/" + relativePath);
            result.put("originalName", originalName);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            result.put("success", false);
            result.put("error", "上传失败: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}

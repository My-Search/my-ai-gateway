package com.myai.gateway.controller.api;

import com.myai.gateway.config.JwtTokenProvider;
import com.myai.gateway.service.AdminConfigService;
import com.myai.gateway.service.ApiKeyService;
import com.myai.gateway.service.ChannelService;
import com.myai.gateway.service.ModelService;
import com.myai.gateway.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台「认证 + 仪表盘」REST API 控制器
 * <p>从原 {@link AdminApiController} 拆分而来（P2 架构：巨型类拆分），
 * 承载管理员登录/登出/初始化和仪表盘统计接口。路径前缀与行为与原实现完全一致。</p>
 */
@RestController
@RequestMapping("/admin/api")
public class AdminAuthController {

    private final AdminConfigService adminConfigService;
    private final JwtTokenProvider jwtTokenProvider;
    private final StatsService statsService;
    private final ChannelService channelService;
    private final ModelService modelService;
    private final ApiKeyService apiKeyService;

    public AdminAuthController(AdminConfigService adminConfigService,
                               JwtTokenProvider jwtTokenProvider,
                               StatsService statsService,
                               ChannelService channelService,
                               ModelService modelService,
                               ApiKeyService apiKeyService) {
        this.adminConfigService = adminConfigService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.statsService = statsService;
        this.channelService = channelService;
        this.modelService = modelService;
        this.apiKeyService = apiKeyService;
    }

    @GetMapping(value = "/auth/check", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> checkAuth(HttpServletRequest request) {
        boolean authenticated = false;

        // 方式1：JWT Token 认证
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            authenticated = jwtTokenProvider.validateToken(token);
        }

        // 方式2：Session 认证（向后兼容）
        if (!authenticated) {
            HttpSession session = request.getSession(false);
            authenticated = session != null && session.getAttribute("adminUser") != null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", authenticated);
        result.put("hasAdminAccount", adminConfigService.hasAdminAccount());
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/auth/login", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        Map<String, Object> result = new LinkedHashMap<>();

        if (!adminConfigService.hasAdminAccount()) {
            result.put("success", false);
            result.put("error", "请先设置管理员账号");
            return ResponseEntity.ok(result);
        }

        if (adminConfigService.verify(username, password)) {
            // 生成 JWT Token
            String token = jwtTokenProvider.generateToken(username);
            HttpSession session = request.getSession(true);
            session.setAttribute("adminUser", username);
            session.setMaxInactiveInterval(8 * 60 * 60);
            result.put("success", true);
            result.put("username", username);
            result.put("token", token);
        } else {
            result.put("success", false);
            result.put("error", "用户名或密码错误");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/auth/setup", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> setup(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        String confirmPassword = body.get("confirmPassword");
        Map<String, Object> result = new LinkedHashMap<>();

        if (!password.equals(confirmPassword)) {
            result.put("success", false);
            result.put("error", "两次输入的密码不一致");
            return ResponseEntity.ok(result);
        }
        if (password.length() < 6) {
            result.put("success", false);
            result.put("error", "密码长度至少6位");
            return ResponseEntity.ok(result);
        }

        boolean success = adminConfigService.setAdminAccount(username, password);
        if (!success) {
            result.put("success", false);
            result.put("error", "管理员账号已存在");
            return ResponseEntity.ok(result);
        }

        // 生成 JWT Token
        String token = jwtTokenProvider.generateToken(username);
        HttpSession session = request.getSession(true);
        session.setAttribute("adminUser", username);
        session.setMaxInactiveInterval(8 * 60 * 60);
        result.put("success", true);
        result.put("token", token);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/auth/logout", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/dashboard/stats", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> dashboardStats(
            @RequestParam(defaultValue = "today") String channelRankPeriod,
            @RequestParam(defaultValue = "today") String modelRankPeriod,
            @RequestParam(required = false) String date) {
        Map<String, Object> stats = statsService.getDashboardStats(channelRankPeriod, modelRankPeriod, date);
        stats.put("channelCount", channelService.listAll().size());
        stats.put("customModelCount", modelService.listAll().size());
        stats.put("apiKeyCount", apiKeyService.listAll().size());
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取今日小时级请求趋势（折线图数据）
     *
     * @param mode 趋势模式：all（全部，默认）/ entry（入口模型）/ channel（渠道模型）
     */
    @GetMapping(value = "/dashboard/today-trend", produces = "application/json;charset=UTF-8")
    public ResponseEntity<Map<String, Object>> todayTrend(
            @RequestParam(defaultValue = "all") String mode,
            @RequestParam(required = false) String date) {
        return ResponseEntity.ok(statsService.getTodayHourlyTrend(mode, date));
    }
}

package com.myai.gateway.controller.api;

/**
 * 管理后台 REST API 控制器（历史聚合入口）
 * <p>
 * P2 架构（巨型类拆分）已完成，原 {@code AdminApiController} 中承载的端点已按领域拆分为独立控制器，
 * 每个控制器保持相同的 {@code /admin/api} 路径前缀与行为，本类仅作占位与标记，不再承载任何端点：
 * </p>
 * <ul>
 *   <li>认证 + 仪表盘 → {@link AdminAuthController}</li>
 *   <li>API Key 管理 → {@link AdminApiKeyController}</li>
 *   <li>对话测试 → {@link AdminChatController}</li>
 *   <li>渠道管理 → {@link AdminChannelController}</li>
 *   <li>模型管理 → {@link AdminModelController}</li>
 *   <li>日志（含 SSE）→ {@link AdminLogController}</li>
 *   <li>系统配置 + 文件上传 → {@link AdminConfigController}</li>
 *   <li>多模态规则 / 提示注入 → {@link AdminMultiModalController}</li>
 * </ul>
 */
public final class AdminApiController {
    private AdminApiController() {
    }
}

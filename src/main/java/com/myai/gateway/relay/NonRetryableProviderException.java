package com.myai.gateway.relay;

/**
 * 不可重试的提供商异常
 *
 * <p>当上游提供商返回 400 请求体格式不正确或 429 限流等客户端错误时抛出此异常，
 * 用于通知中继逻辑跳过重试和熔断，直接重路由到下一个候选。</p>
 */
public class NonRetryableProviderException extends RuntimeException {

    /** HTTP 状态码 */
    private final int httpStatus;

    public NonRetryableProviderException(int httpStatus, String message) {
        super("Provider client error: " + httpStatus + " body: " + message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}

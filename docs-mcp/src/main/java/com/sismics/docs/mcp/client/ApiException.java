package com.sismics.docs.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * ZDEDMS REST API 调用的统一异常。{@link ZdedmsApiClient} 在收到 4xx/5xx 时构造。
 *
 * <p>ZDEDMS 错误响应体为 {@code {"type": "...", "message": "..."}}（参考
 * {@code docs-web-common/.../exception/ClientException} 与
 * {@code docs-web-common/.../util/JsonUtil}）。常见的 type 取值：</p>
 *
 * <ul>
 *   <li>{@code ForbiddenError} — 鉴权失败 / 权限不足</li>
 *   <li>{@code ValidationCodeRequired} — TOTP/2FA 触发，需要 6 位 code</li>
 *   <li>{@code ValidationError} — 参数校验失败</li>
 *   <li>{@code QuotaReached} — 存储配额满</li>
 *   <li>{@code NotFound} — 资源不存在</li>
 *   <li>{@code AlreadyExistingGroup} 等业务错误</li>
 * </ul>
 *
 * <p>当响应体不是合法 JSON 或没有 type/message 字段时，只回退到 status code。错误信息
 * 通过 stderr logback 写日志，stdout 仍保持 JSON-RPC 干净。</p>
 */
public class ApiException extends IOException {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ApiException.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int statusCode;
    private final String errorType;
    private final String errorMessage;
    private final String rawBody;

    public ApiException(int statusCode, String errorType, String errorMessage, String rawBody) {
        super(formatMessage(statusCode, errorType, errorMessage));
        this.statusCode = statusCode;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.rawBody = rawBody;
    }

    /**
     * 从 4xx/5xx 响应构造：尝试解析 body 中的 type/message 字段，回退到 status code。
     */
    public static ApiException fromResponse(int statusCode, String body) {
        String type = null;
        String message = null;
        if (body != null && !body.isEmpty()) {
            try {
                JsonNode node = MAPPER.readTree(body);
                if (node != null && node.isObject()) {
                    JsonNode t = node.get("type");
                    if (t != null && t.isTextual()) {
                        type = t.asText();
                    }
                    JsonNode m = node.get("message");
                    if (m != null && m.isTextual()) {
                        message = m.asText();
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse error response body as JSON (status={}): {}",
                        statusCode, e.getMessage());
            }
        }
        return new ApiException(statusCode, type, message, body);
    }

    private static String formatMessage(int status, String type, String message) {
        StringBuilder sb = new StringBuilder("ZDEDMS API error: HTTP ").append(status);
        if (type != null) {
            sb.append(" [").append(type).append("]");
        }
        if (message != null) {
            sb.append(" - ").append(message);
        }
        return sb.toString();
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRawBody() {
        return rawBody;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }

    /** 是否鉴权失败（用于触发 AuthProvider.onAuthFailure） */
    public boolean isUnauthorized() {
        return statusCode == 401;
    }
}

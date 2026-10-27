package com.sismics.docs.mcp.auth;

/**
 * 鉴权不可用：refresh token 失败、token 缺失、cookie 模式 401 等。抛出后用户需
 * 重新执行 {@code zdedms login}。
 *
 * <p>对应 MCP JSON-RPC 错误码 {@code -32004}（自定义"鉴权失败"）。</p>
 */
public class AuthRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthRequiredException(String message) {
        super(message);
    }

    public AuthRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.sismics.docs.mcp.auth;

/**
 * 24h 客户端强过期触发（即使 ZDEDMS 服务端 cookie 仍有 1 天有效期，
 * docs-mcp 客户端也强制重新登录）。抛出后用户需重新执行 {@code zdedms login}。
 *
 * <p>对应 MCP JSON-RPC 错误码 {@code -32004}（与 {@link AuthRequiredException} 共用，
 * 但 message 会带"24h expired"标记便于 AI agent 引导用户重跑 login）。</p>
 */
public class AuthExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthExpiredException(String message) {
        super(message);
    }
}

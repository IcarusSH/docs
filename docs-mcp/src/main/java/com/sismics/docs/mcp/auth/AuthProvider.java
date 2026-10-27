package com.sismics.docs.mcp.auth;

import com.sismics.docs.mcp.client.ApiException;
import com.sismics.docs.mcp.client.ZdedmsApiClient;
import com.sismics.docs.mcp.config.McpConfig;
import okhttp3.Request;

import java.io.IOException;

/**
 * 鉴权策略接口。{@link ZdedmsApiClient} 在每次请求前调用
 * {@link #applyAuth(Request.Builder)} 注入 {@code Authorization} 或 {@code Cookie} 头。
 *
 * <p>两个实现：</p>
 * <ul>
 *   <li>{@code CookieAuthProvider} — username/password 登录后注入
 *       {@code Cookie: auth_token=...}</li>
 *   <li>{@code CasdoorJwtAuthProvider} — Casdoor OIDC 拿到 id_token 后注入
 *       {@code Authorization: Bearer ...}</li>
 * </ul>
 *
 * <h2>24h 客户端强过期</h2>
 *
 * <p>{@link #ensureAuth()} 在每次 tool call / CLI 命令前被
 * {@code JsonRpcDispatcher} / {@code CliMain} 调用：</p>
 * <ol>
 *   <li>若 {@link McpConfig#isExpired(long)} 为 true，抛 {@link AuthExpiredException}</li>
 *   <li>JWT 模式下，token 将在 60s 内过期时尝试 refresh_token 刷新；失败抛
 *       {@link AuthRequiredException} 让用户重新 {@code zdedms login}</li>
 *   <li>否则 no-op</li>
 * </ol>
 */
public interface AuthProvider {

    /**
     * 注入鉴权头到 OkHttp 请求。
     * <p>实现必须是幂等且无副作用的，每次请求都会调。</p>
     */
    void applyAuth(Request.Builder builder);

    /**
     * 主循环 / 工具调用前调用。检查 24h 过期、刷新 JWT 等。
     *
     * @throws AuthExpiredException 24h 已过，需重新 {@code zdedms login}
     * @throws AuthRequiredException 鉴权不可用（refresh 失败、token 缺失等）
     */
    void ensureAuth() throws AuthExpiredException, AuthRequiredException;

    /**
     * 401 响应时回调，看是否可自动恢复。
     * <p>默认 no-op；JWT 模式可在此尝试 refresh_token 刷新；Cookie 模式直接抛错。</p>
     */
    default void onAuthFailure(ZdedmsApiClient client, ApiException error) throws IOException {
        throw new AuthRequiredException("Authentication failed: " + error.getMessage());
    }

    /**
     * 当前使用何种鉴权方式（用于日志/调试）。
     */
    String getAuthType();
}

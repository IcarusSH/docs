package com.sismics.docs.mcp.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * ZDEDMS MCP 客户端的本地配置，序列化为 {@code ~/.teedy-mcp/config.json}。
 *
 * <p>由 {@link ConfigStore} 读写；由 {@code docs-mcp} 启动时读出后构造
 * {@code AuthProvider}（Cookie 或 CasdoorJwt）。</p>
 *
 * <h2>24h 客户端强过期</h2>
 *
 * <p>安全设计：ZDEDMS 服务端的 1 天 session cookie ({@code remember=false}) 已经
 * 比"20 年长期 cookie"短，但客户端再叠 24h 主动过期（{@link #isExpired(long)}），
 * 防止 token 泄露后被长期滥用。超过 24h 后 ZDEDMS 端 cookie 可能还有效，
 * 但 docs-mcp 仍要求用户重新 {@code zdedms login}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpConfig {

    /** ZDEDMS 实例的根 URL，例：{@code https://docs.example.com} */
    @JsonProperty("server_url")
    public String serverUrl;

    /** Casdoor 实例 URL。{@code auth_type=casdoor_jwt} 时必填 */
    @JsonProperty("casdoor_server_url")
    public String casdoorServerUrl;

    @JsonProperty("casdoor_client_id")
    public String casdoorClientId;

    /** Device Flow 模式下保存的 client_secret。authorization-code 不存 */
    @JsonProperty("casdoor_client_secret")
    public String casdoorClientSecret;

    /** 当前使用的鉴权方式 */
    @JsonProperty("auth_type")
    public String authType;

    /** {@code casdoor_jwt} 模式下的 id_token / access_token 值 */
    @JsonProperty("token")
    public String token;

    /** JWT 模式的 token 过期时间（epoch 秒）。cookie 模式为 null */
    @JsonProperty("token_expires_at")
    public Long tokenExpiresAt;

    /** JWT 模式下的 refresh_token（用于过期前主动刷新） */
    @JsonProperty("refresh_token")
    public String refreshToken;

    /** {@code cookie} 模式下的 auth_token 值 */
    @JsonProperty("cookie_auth_token")
    public String cookieAuthToken;

    /** 登录用户名（仅做展示用，不用于鉴权） */
    @JsonProperty("username")
    public String username;

    /**
     * 客户端登录时间（epoch ms）。用于 {@link #isExpired(long)} 的 24h 客户端强过期。
     * 必填字段；{@code null} 视为已过期（强制重新登录）。
     */
    @JsonProperty("login_at")
    public Long loginAt;

    /** 24h 强过期的毫秒数 */
    public static final long LOGIN_TTL_MS = 24L * 60L * 60L * 1000L;

    /** 鉴权方式常量 */
    public static final String AUTH_TYPE_COOKIE = "cookie";
    public static final String AUTH_TYPE_CASDOOR_JWT = "casdoor_jwt";

    /**
     * 判断当前是否超过 24h 客户端强过期。
     *
     * @param nowMs 当前 epoch 毫秒（注入便于测试）
     * @return true 表示已过期或未登录（loginAt 为 null）
     */
    public boolean isExpired(long nowMs) {
        if (loginAt == null) {
            return true;
        }
        return (nowMs - loginAt) >= LOGIN_TTL_MS;
    }

    /**
     * 是否已配置好可用鉴权。
     *
     * <p><b>注意</b>：必须标 {@code @JsonIgnore}，否则 Jackson 会把 {@code isXxx()}
     * 当作 bean getter，把 {@code "authenticated": true} 写进 config.json；下次
     * 反序列化时该字段未知会让 {@code ConfigStore.load()} 失败。</p>
     */
    @JsonIgnore
    public boolean isAuthenticated() {
        if (serverUrl == null || serverUrl.isEmpty()) {
            return false;
        }
        if (AUTH_TYPE_COOKIE.equals(authType)) {
            return cookieAuthToken != null && !cookieAuthToken.isEmpty();
        }
        if (AUTH_TYPE_CASDOOR_JWT.equals(authType)) {
            return token != null && !token.isEmpty();
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McpConfig)) {
            return false;
        }
        McpConfig that = (McpConfig) o;
        return Objects.equals(serverUrl, that.serverUrl)
                && Objects.equals(authType, that.authType)
                && Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverUrl, authType, username);
    }
}

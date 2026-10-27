package com.sismics.docs.mcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Casdoor OIDC Device Authorization Grant（RFC 8628）的两类响应。
 *
 * <p>端点路径：</p>
 * <ul>
 *   <li>请求 device_code：{@code POST {CASDOOR}/api/device-auth}</li>
 *   <li>轮询 token：{@code POST {CASDOOR}/api/login/oauth/access_token}
 *       （grant_type=urn:ietf:params:oauth:grant-type:device_code）</li>
 * </ul>
 *
 * <p>注意 Casdoor 的 device_authorization 端点是 {@code /api/device-auth}，
 * 不是 RFC 8628 默认的 {@code /oauth/device/code}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceFlowResponse {

    // ===== DeviceAuthorizationResponse（POST /api/device-auth 响应）=====

    @JsonProperty("device_code")
    public String deviceCode;

    @JsonProperty("user_code")
    public String userCode;

    @JsonProperty("verification_uri")
    public String verificationUri;

    /** Casdoor 扩展字段：可直接打开的 URL（含 user_code），适合做 QR 码 */
    @JsonProperty("verification_uri_complete")
    public String verificationUriComplete;

    @JsonProperty("expires_in")
    public Integer expiresIn;

    /** Casdoor 给的轮询间隔（秒），默认 5 */
    @JsonProperty("interval")
    public Integer interval;

    // ===== TokenResponse（轮询 /api/login/oauth/access_token 成功响应）=====

    @JsonProperty("access_token")
    public String accessToken;

    @JsonProperty("id_token")
    public String idToken;

    @JsonProperty("refresh_token")
    public String refreshToken;

    @JsonProperty("token_type")
    public String tokenType;

    @JsonProperty("expires_in")
    public Integer tokenExpiresIn;

    @JsonProperty("scope")
    public String scope;

    // ===== TokenErrorResponse（轮询中常见的"未授权"错误）=====

    @JsonProperty("error")
    public String error;

    @JsonProperty("error_description")
    public String errorDescription;

    /** 是否还在等待用户授权 */
    @JsonIgnore
    public boolean isAuthorizationPending() {
        return "authorization_pending".equals(error);
    }

    /** Casdoor 让客户端放慢轮询（一般 5s） */
    @JsonIgnore
    public boolean isSlowDown() {
        return "slow_down".equals(error);
    }

    /** 用户拒绝授权 / device_code 过期 / 其它致命错误 */
    @JsonIgnore
    public boolean isFatal() {
        return "access_denied".equals(error) || "expired_token".equals(error);
    }
}

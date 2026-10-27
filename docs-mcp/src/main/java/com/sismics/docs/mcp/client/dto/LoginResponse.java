package com.sismics.docs.mcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /api/user/login} 响应。
 *
 * <p>ZDEDMS 服务端实际返回的是空 JSON {@code {}}（{@code UserResource.java:326-327}），
 * cookie 通过 {@code Set-Cookie: auth_token=...} 头下发，由 OkHttp 拦截。</p>
 *
 * <p>此 DTO 保留是因为可能未来服务端会在 body 加 username/role 等元信息；当前
 * 只需根据 HTTP 200 + 头部 Set-Cookie 判定登录成功。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginResponse {

    /** ZDEDMS 4xx 错误时返回的错误类型，例如 "ValidationCodeRequired"（TOTP 触发）*/
    @JsonProperty("type")
    public String type;

    @JsonProperty("message")
    public String message;

    /** 是否触发 TOTP 二次验证（基于 error type 推断） */
    @JsonIgnore
    public boolean isTotpRequired() {
        return "ValidationCodeRequired".equals(type);
    }
}

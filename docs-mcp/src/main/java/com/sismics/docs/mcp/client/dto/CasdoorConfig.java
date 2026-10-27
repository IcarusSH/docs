package com.sismics.docs.mcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Casdoor OAuth 客户端配置，对应 {@code GET /api/app/config_casdoor} 响应。
 *
 * <p>用于 Device Flow 登录前获取 {@code casdoor_server_url / client_id / client_secret}。
 * 字段定义见 {@code docs-web/.../rest/resource/AppResource.java} {@code configCasdoor} 端点。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CasdoorConfig {

    @JsonProperty("casdoor_server_url")
    public String casdoorServerUrl;

    @JsonProperty("casdoor_client_id")
    public String casdoorClientId;

    @JsonProperty("casdoor_client_secret")
    public String casdoorClientSecret;

    @JsonProperty("casdoor_organization")
    public String casdoorOrganization;

    @JsonProperty("casdoor_application")
    public String casdoorApplication;
}

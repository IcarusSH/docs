package com.sismics.docs.mcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ZDEDMS 应用级配置信息，对应 {@code GET /api/app} 响应。
 *
 * <p>字段定义见 {@code docs-web/.../rest/resource/AppResource.java:105-122}。</p>
 *
 * <p>主要用途是探测 {@link #casdoorEnabled}：true 时 docs-mcp 走 Casdoor OIDC
 * Device Flow；false 时回退到 username/password 登录。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppInfo {

    @JsonProperty("current_version")
    public String currentVersion;

    @JsonProperty("min_version")
    public String minVersion;

    @JsonProperty("guest_login")
    public Boolean guestLogin;

    @JsonProperty("ocr_enabled")
    public Boolean ocrEnabled;

    /** 关键字段：决定走 Casdoor Device Flow 还是密码登录 */
    @JsonProperty("casdoor_enabled")
    public Boolean casdoorEnabled;

    @JsonProperty("watermark_enabled")
    public Boolean watermarkEnabled;

    @JsonProperty("default_language")
    public String defaultLanguage;

    @JsonProperty("queued_tasks")
    public Long queuedTasks;

    @JsonProperty("total_memory")
    public Long totalMemory;

    @JsonProperty("free_memory")
    public Long freeMemory;

    @JsonProperty("document_count")
    public Long documentCount;

    @JsonProperty("active_user_count")
    public Long activeUserCount;

    @JsonProperty("global_storage_current")
    public Long globalStorageCurrent;

    @JsonProperty("global_storage_quota")
    public Long globalStorageQuota;
}

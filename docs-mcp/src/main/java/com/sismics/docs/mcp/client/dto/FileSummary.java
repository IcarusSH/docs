package com.sismics.docs.mcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文件元数据，对应 {@code docs-web-common/.../rest/util/RestUtil.fileToJsonObjectBuilder} 的输出。
 * 用作 {@link DocumentSummary#files} 数组元素，也是 {@code GET /api/file/list} 的单元素。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileSummary {

    @JsonProperty("id")
    public String id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("version")
    public Integer version;

    @JsonProperty("mimetype")
    public String mimetype;

    @JsonProperty("document_id")
    public String documentId;

    @JsonProperty("create_date")
    public Long createDate;

    @JsonProperty("size")
    public Long size;

    @JsonProperty("processing")
    public Boolean processing;

    /** 来自 commit 171ce247：FileResource.fileToJsonObjectBuilder 加 reminder_date/reminded 输出 */
    @JsonProperty("reminder_date")
    public Long reminderDate;

    @JsonProperty("reminded")
    public Boolean reminded;
}

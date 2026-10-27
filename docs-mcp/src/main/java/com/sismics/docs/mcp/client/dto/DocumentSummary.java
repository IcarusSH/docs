package com.sismics.docs.mcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 单个文档的简要信息，对应 {@code GET /api/document/list} 中 documents[] 数组元素，
 * 也对应 {@code GET /api/document/{id}} 和 {@code PUT /api/document} 的顶层结构。
 *
 * <p>字段定义见 {@code docs-web/.../rest/resource/DocumentResource.java:1123}
 * {@code createDocumentObjectBuilder()}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentSummary {

    @JsonProperty("id")
    public String id;

    @JsonProperty("title")
    public String title;

    @JsonProperty("description")
    public String description;

    @JsonProperty("language")
    public String language;

    @JsonProperty("shared")
    public Boolean shared;

    @JsonProperty("create_date")
    public Long createDate;

    @JsonProperty("update_date")
    public Long updateDate;

    @JsonProperty("active_route")
    public Boolean activeRoute;

    @JsonProperty("current_step_name")
    public String currentStepName;

    @JsonProperty("highlight")
    public String highlight;

    @JsonProperty("file_count")
    public Long fileCount;

    @JsonProperty("file_id")
    public String fileId;

    @JsonProperty("tags")
    public List<Tag> tags;

    /** 仅当 list 端点带 {@code files=true} 时填充 */
    @JsonProperty("files")
    public List<FileSummary> files;

    /** 列表结果包装。{@code GET /api/document/list} 返回 {@code {total, documents[], suggestions[]}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ListResult {
        @JsonProperty("total")
        public Long total;
        @JsonProperty("documents")
        public List<DocumentSummary> documents;
        @JsonProperty("suggestions")
        public List<String> suggestions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tag {
        @JsonProperty("id")
        public String id;
        @JsonProperty("name")
        public String name;
        @JsonProperty("color")
        public String color;
    }
}

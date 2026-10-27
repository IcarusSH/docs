package com.sismics.docs.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sismics.docs.mcp.auth.AuthProvider;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ZDEDMS REST API 客户端。OkHttp 4.10.0 封装。构造参数注入 {@link AuthProvider}，
 * 每次请求前 {@code authProvider.applyAuth()} 注入鉴权头。
 *
 * <p>对外只暴露 5 个核心方法：</p>
 * <ul>
 *   <li>{@link #getJson} / {@link #getJsonAs} — 读 JSON 资源</li>
 *   <li>{@link #postJson} / {@link #postForm} — 写资源（JSON body / form 表单）</li>
 *   <li>{@link #putForm} — 走 {@code multipart/form-data} 的 PUT（文件上传场景）</li>
 *   <li>{@link #uploadMultipart} — 走 {@code multipart/form-data} 的 PUT/PUT 含文件</li>
 *   <li>{@link #downloadBinary} — 走 {@code binary/octet-stream} 的下载</li>
 * </ul>
 *
 * <p>错误处理：4xx/5xx 一律抛 {@link ApiException}，由调用方（{@code McpTool} /
 * CLI 子命令）决定如何呈现给 AI agent 或用户。</p>
 */
public class ZdedmsApiClient {

    private static final Logger log = LoggerFactory.getLogger(ZdedmsApiClient.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    /** 复用 Jackson 单例，禁止 fail-on-unknown-properties（ZDEDMS 可能加字段） */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final AuthProvider authProvider;
    private final OkHttpClient httpClient;

    public ZdedmsApiClient(String baseUrl, AuthProvider authProvider) {
        this(baseUrl, authProvider, defaultHttpClient());
    }

    /** 测试用：允许注入自定义 OkHttpClient（如 MockWebServer 走 trustAll） */
    public ZdedmsApiClient(String baseUrl, AuthProvider authProvider, OkHttpClient httpClient) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must not be empty");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authProvider = authProvider;
        this.httpClient = httpClient;
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    // ====== JSON GET ======

    /** GET 任意路径并把响应体解析为 {@link JsonNode}（不预设 schema） */
    public JsonNode getJson(String path, Map<String, String> queryParams) throws IOException {
        Request req = newAuthedRequest(path)
                .url(buildUrl(path, queryParams))
                .get()
                .build();
        return executeJson(req);
    }

    /** GET 并反序列化为指定类型 */
    public <T> T getJsonAs(String path, Map<String, String> queryParams, Class<T> type) throws IOException {
        JsonNode node = getJson(path, queryParams);
        return MAPPER.treeToValue(node, type);
    }

    public <T> T getJsonAs(String path, Map<String, String> queryParams, TypeReference<T> typeRef) throws IOException {
        JsonNode node = getJson(path, queryParams);
        return MAPPER.treeToValue(node, typeRef);
    }

    // ====== JSON POST / PUT ======

    public JsonNode postJson(String path, Object body) throws IOException {
        String bodyStr = MAPPER.writeValueAsString(body);
        Request req = newAuthedRequest(path)
                .url(buildUrl(path, null))
                .post(RequestBody.create(bodyStr, JSON))
                .build();
        return executeJson(req);
    }

    public JsonNode postJson(String path, Map<String, String> queryParams, Object body) throws IOException {
        String bodyStr = MAPPER.writeValueAsString(body);
        Request req = newAuthedRequest(path)
                .url(buildUrl(path, queryParams))
                .post(RequestBody.create(bodyStr, JSON))
                .build();
        return executeJson(req);
    }

    /** 走 application/x-www-form-urlencoded 的 POST（ZDEDMS 大部分写端点用这种） */
    public JsonNode postForm(String path, Map<String, String> formParams) throws IOException {
        FormBody.Builder fb = new FormBody.Builder();
        if (formParams != null) {
            for (Map.Entry<String, String> e : formParams.entrySet()) {
                fb.add(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        Request req = newAuthedRequest(path)
                .url(buildUrl(path, null))
                .post(fb.build())
                .build();
        return executeJson(req);
    }

    /** PUT 走 multipart/form-data。{@code parts} 可包含字符串字段和文件（{@link Path} 值） */
    public JsonNode uploadMultipart(String path, Map<String, Object> parts) throws IOException {
        MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
        if (parts != null) {
            for (Map.Entry<String, Object> e : parts.entrySet()) {
                if (e.getValue() instanceof Path) {
                    Path p = (Path) e.getValue();
                    String fileName = p.getFileName().toString();
                    String mime = Files.probeContentType(p);
                    mb.addFormDataPart(e.getKey(), fileName,
                            RequestBody.create(p.toFile(),
                                    MediaType.parse(mime == null ? "application/octet-stream" : mime)));
                } else {
                    mb.addFormDataPart(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
                }
            }
        }
        Request req = newAuthedRequest(path)
                .url(buildUrl(path, null))
                .put(mb.build())
                .build();
        return executeJson(req);
    }

    /** 与 uploadMultipart 类似，但 formParams 都是字符串（无文件），走 multipart 也 OK */
    public JsonNode putForm(String path, Map<String, String> formParams) throws IOException {
        MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
        if (formParams != null) {
            for (Map.Entry<String, String> e : formParams.entrySet()) {
                mb.addFormDataPart(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        Request req = newAuthedRequest(path)
                .url(buildUrl(path, null))
                .put(mb.build())
                .build();
        return executeJson(req);
    }

    // ====== Binary download ======

    /**
     * GET 走二进制流。把响应体写到 {@code target}，返回字节数。
     * <p>路径示例：{@code /api/file/{id}/data?size=thumb}</p>
     */
    public long downloadBinary(String path, Map<String, String> queryParams, Path target) throws IOException {
        HttpUrl url = buildUrl(path, queryParams);
        Request req = newAuthedRequest(path)
                .url(url)
                .get()
                .build();
        long bytes = 0;
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String err = resp.body() == null ? "" : resp.body().string();
                throw ApiException.fromResponse(resp.code(), err);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new ApiException(resp.code(), "EmptyBody", "Response body is null", "");
            }
            try (InputStream in = body.byteStream()) {
                bytes = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.debug("Downloaded {} bytes from {} -> {}", bytes, path, target);
        return bytes;
    }

    // ====== Low-level ======

    /**
     * 构造一个带鉴权头的 Request.Builder（不指定 method，留给调用方）。
     * 给需要自定义 method/header 的场景（如返回 raw 响应）使用。
     */
    public Request.Builder newAuthedRequest(String path) {
        Request.Builder b = new Request.Builder();
        if (authProvider != null) {
            authProvider.applyAuth(b);
        }
        b.header("Accept", "application/json");
        b.header("User-Agent", "zdedms-mcp/1.0");
        return b;
    }

    private JsonNode executeJson(Request req) throws IOException {
        try (Response resp = httpClient.newCall(req).execute()) {
            String body = "";
            if (resp.body() != null) {
                body = resp.body().string();
            }
            if (!resp.isSuccessful()) {
                throw ApiException.fromResponse(resp.code(), body);
            }
            if (body.isEmpty()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(body);
        }
    }

    private HttpUrl buildUrl(String path, Map<String, String> queryParams) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        HttpUrl.Builder url = HttpUrl.parse(baseUrl + path).newBuilder();
        if (queryParams != null) {
            for (Map.Entry<String, String> e : queryParams.entrySet()) {
                if (e.getValue() != null) {
                    url.addQueryParameter(e.getKey(), e.getValue());
                }
            }
        }
        return url.build();
    }
}

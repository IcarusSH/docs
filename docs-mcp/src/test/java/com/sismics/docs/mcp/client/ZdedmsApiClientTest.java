package com.sismics.docs.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sismics.docs.mcp.auth.AuthProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ZdedmsApiClient} 单元测试。用 OkHttp MockWebServer 模拟 ZDEDMS 响应，
 * 覆盖 GET / POST / PUT / Upload / Download / 401 / 403 / 500 / 4xx 空 body / 鉴权头注入。
 */
public class ZdedmsApiClientTest {

    private MockWebServer server;
    private ZdedmsApiClient client;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("").toString();
        // MockWebServer 需要禁用重定向跟随，跟默认 client 一致即可
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        client = new ZdedmsApiClient(baseUrl, new NoopAuth(), http);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    // ====== GET ======

    @Test
    public void getJson_returnsParsedResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"total\":1,\"documents\":[{\"id\":\"d1\",\"title\":\"t\"}]}"));

        JsonNode node = client.getJson("/api/document/list", null);
        assertEquals(1, node.get("total").asInt());
        assertEquals("d1", node.get("documents").get(0).get("id").asText());

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/document/list", req.getPath());
    }

    @Test
    public void getJson_withQueryParams_appendsToPath() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("limit", "10");
        q.put("offset", "0");
        q.put("search[full]", "发票");
        client.getJson("/api/document/list", q);
        RecordedRequest req = server.takeRequest();
        // 顺序可能因 URL 编码而不同，校验关键参数都在
        assertTrue("query string should contain limit=10: " + req.getPath(),
                req.getPath().contains("limit=10"));
        assertTrue(req.getPath().contains("search%5Bfull%5D") || req.getPath().contains("search[full]"));
    }

    @Test
    public void getJsonAs_deserializesToType() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"current_version\":\"1.11\",\"casdoor_enabled\":true}"));
        com.sismics.docs.mcp.client.dto.AppInfo info =
                client.getJsonAs("/api/app", null, com.sismics.docs.mcp.client.dto.AppInfo.class);
        assertNotNull(info);
        assertEquals("1.11", info.currentVersion);
        assertEquals(Boolean.TRUE, info.casdoorEnabled);
    }

    // ====== POST JSON ======

    @Test
    public void postJson_sendsJsonBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"newdoc\"}"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "测试");
        body.put("language", "chi_sim");
        JsonNode resp = client.postJson("/api/document/mark", body);
        assertEquals("newdoc", resp.get("id").asText());
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        String ct = req.getHeader("Content-Type");
        assertNotNull(ct);
        assertTrue("expected JSON content-type, got: " + ct, ct.startsWith("application/json"));
        String bodyStr = req.getBody().readUtf8();
        assertTrue("body should contain title: " + bodyStr, bodyStr.contains("\"title\""));
    }

    @Test
    public void postForm_sendsFormEncodedBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        Map<String, String> form = new HashMap<>();
        form.put("default_language", "chi_sim");
        client.postForm("/api/app/config", form);
        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        String ct = req.getHeader("Content-Type");
        assertNotNull(ct);
        assertTrue("expected form content-type, got: " + ct, ct.startsWith("application/x-www-form-urlencoded"));
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("default_language=chi_sim"));
    }

    // ====== Upload (multipart PUT) ======

    @Test
    public void uploadMultipart_sendsFileAndFields() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"newfile\"}"));
        Path tmp = Files.createTempFile("test-", ".txt");
        Files.write(tmp, "hello world".getBytes("UTF-8"));
        try {
            Map<String, Object> parts = new LinkedHashMap<>();
            parts.put("id", "docid-1");
            parts.put("file", tmp);
            JsonNode resp = client.uploadMultipart("/api/file", parts);
            assertEquals("newfile", resp.get("id").asText());
            RecordedRequest req = server.takeRequest();
            assertEquals("PUT", req.getMethod());
            String ct = req.getHeader("Content-Type");
            assertNotNull(ct);
            assertTrue("expected multipart, got: " + ct, ct.startsWith("multipart/form-data"));
            String body = req.getBody().readUtf8();
            assertTrue("body should contain id field: " + body, body.contains("docid-1"));
            assertTrue("body should contain filename: " + body, body.contains("test-"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ====== Download ======

    @Test
    public void downloadBinary_writesToTarget() throws Exception {
        byte[] payload = "binary-content".getBytes("UTF-8");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(new okio.Buffer().write(payload)));
        Path target = Files.createTempFile("dl-", ".bin");
        try {
            long bytes = client.downloadBinary("/api/file/abc/data", null, target);
            assertEquals(payload.length, bytes);
            assertArrayEquals(payload, Files.readAllBytes(target));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    // ====== Error handling ======

    @Test
    public void fourOhOne_throwsApiExceptionWithStatus() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"type\":\"ForbiddenError\",\"message\":\"Not authenticated\"}"));
        try {
            client.getJson("/api/user", null);
            fail("should have thrown");
        } catch (ApiException e) {
            assertEquals(401, e.getStatusCode());
            assertEquals("ForbiddenError", e.getErrorType());
            assertEquals("Not authenticated", e.getErrorMessage());
            assertTrue(e.isClientError());
            assertTrue(e.isUnauthorized());
        } catch (Exception e) {
            fail("expected ApiException, got: " + e);
        }
    }

    @Test
    public void fourOhThree_throwsApiException() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"type\":\"ForbiddenError\",\"message\":\"admin only\"}"));
        try {
            client.getJson("/api/user/list", null);
            fail("should have thrown");
        } catch (ApiException e) {
            assertEquals(403, e.getStatusCode());
            assertEquals("admin only", e.getErrorMessage());
        } catch (Exception e) {
            fail("expected ApiException, got: " + e);
        }
    }

    @Test
    public void four04_throwsApiExceptionWithType() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"type\":\"NotFound\",\"message\":\"document xyz not found\"}"));
        try {
            client.getJson("/api/document/xyz", null);
            fail("should have thrown");
        } catch (ApiException e) {
            assertEquals(404, e.getStatusCode());
            assertEquals("NotFound", e.getErrorType());
        } catch (Exception e) {
            fail("expected ApiException, got: " + e);
        }
    }

    @Test
    public void fiveHundred_throwsApiException() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"type\":\"ServerError\",\"message\":\"oops\"}"));
        try {
            client.getJson("/api/document/list", null);
            fail("should have thrown");
        } catch (ApiException e) {
            assertEquals(500, e.getStatusCode());
            assertTrue(e.isServerError());
        } catch (Exception e) {
            fail("expected ApiException, got: " + e);
        }
    }

    @Test
    public void fourXxWithEmptyBody_stillThrows() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody(""));
        try {
            client.getJson("/api/whatever", null);
            fail("should have thrown");
        } catch (ApiException e) {
            assertEquals(400, e.getStatusCode());
            // 没有 type / message 字段
            assertEquals(null, e.getErrorType());
        } catch (Exception e) {
            fail("expected ApiException, got: " + e);
        }
    }

    @Test
    public void fourXxWithNonJsonBody_stillThrows() {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"));
        try {
            client.getJson("/api/whatever", null);
            fail("should have thrown");
        } catch (ApiException e) {
            assertEquals(502, e.getStatusCode());
            assertEquals(null, e.getErrorType());
        } catch (Exception e) {
            fail("expected ApiException, got: " + e);
        }
    }

    // ====== Auth header injection ======

    @Test
    public void authProvider_injectsAuthHeader() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        client = new ZdedmsApiClient(baseUrl, new BearerAuth("test-jwt-xyz"), new OkHttpClient.Builder().build());
        client.getJson("/api/user", null);
        RecordedRequest req = server.takeRequest();
        assertEquals("Bearer test-jwt-xyz", req.getHeader("Authorization"));
        // 默认 User-Agent 也应注入
        assertNotNull(req.getHeader("User-Agent"));
        assertTrue(req.getHeader("User-Agent").contains("zdedms-mcp"));
    }

    @Test
    public void cookieAuthProvider_injectsCookieHeader() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        client = new ZdedmsApiClient(baseUrl, new CookieAuth("test-cookie"), new OkHttpClient.Builder().build());
        client.getJson("/api/user", null);
        RecordedRequest req = server.takeRequest();
        assertEquals("auth_token=test-cookie", req.getHeader("Cookie"));
    }

    // ====== Test helpers ======

    private static class NoopAuth implements AuthProvider {
        @Override
        public void applyAuth(Request.Builder builder) { /* no-op */ }
        @Override
        public void ensureAuth() { /* no-op */ }
        @Override
        public String getAuthType() { return "noop"; }
    }

    private static class BearerAuth implements AuthProvider {
        private final String token;
        BearerAuth(String token) { this.token = token; }
        @Override
        public void applyAuth(Request.Builder builder) { builder.header("Authorization", "Bearer " + token); }
        @Override
        public void ensureAuth() { }
        @Override
        public String getAuthType() { return "test-bearer"; }
    }

    private static class CookieAuth implements AuthProvider {
        private final String token;
        CookieAuth(String token) { this.token = token; }
        @Override
        public void applyAuth(Request.Builder builder) { builder.header("Cookie", "auth_token=" + token); }
        @Override
        public void ensureAuth() { }
        @Override
        public String getAuthType() { return "test-cookie"; }
    }
}

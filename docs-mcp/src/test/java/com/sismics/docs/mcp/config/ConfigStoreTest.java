package com.sismics.docs.mcp.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * {@link ConfigStore} 单元测试。
 *
 * <p>用 {@link TemporaryFolder} 注入临时目录，避免污染真实 home。Posix 权限相关
 * 断言在非 Unix 平台跳过（Windows 不支持 chmod 600）。</p>
 */
public class ConfigStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void load_missingFile_returnsNull() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("config.json");
        ConfigStore store = new ConfigStore(p);
        assertNull(store.load());
    }

    @Test
    public void saveThenLoad_roundTrip() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("config.json");
        ConfigStore store = new ConfigStore(p);

        McpConfig cfg = new McpConfig();
        cfg.serverUrl = "https://docs.example.com";
        cfg.authType = McpConfig.AUTH_TYPE_CASDOOR_JWT;
        cfg.token = "eyJabc.def.ghi";
        cfg.tokenExpiresAt = 1893456000L;
        cfg.refreshToken = "refresh-xyz";
        cfg.username = "alice";
        cfg.loginAt = 1729000000000L;
        store.save(cfg);

        McpConfig loaded = store.load();
        assertNotNull(loaded);
        assertEquals("https://docs.example.com", loaded.serverUrl);
        assertEquals("casdoor_jwt", loaded.authType);
        assertEquals("eyJabc.def.ghi", loaded.token);
        assertEquals(Long.valueOf(1893456000L), loaded.tokenExpiresAt);
        assertEquals("refresh-xyz", loaded.refreshToken);
        assertEquals("alice", loaded.username);
        assertEquals(Long.valueOf(1729000000000L), loaded.loginAt);
    }

    @Test
    public void load_corruptedJson_returnsNull() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("config.json");
        Files.write(p, "this is not json".getBytes(StandardCharsets.UTF_8));
        ConfigStore store = new ConfigStore(p);
        assertNull("corrupted json should be treated as missing", store.load());
    }

    @Test
    public void delete_removesFile() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("config.json");
        ConfigStore store = new ConfigStore(p);
        McpConfig cfg = new McpConfig();
        cfg.serverUrl = "https://x";
        store.save(cfg);
        assertTrue(Files.exists(p));
        assertTrue(store.delete());
        assertFalse(Files.exists(p));
        // 二次 delete 应返回 false
        assertFalse(store.delete());
    }

    @Test
    public void save_createsDirIfMissing() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("subdir/config.json");
        ConfigStore store = new ConfigStore(p);
        assertFalse(Files.exists(p.getParent()));
        McpConfig cfg = new McpConfig();
        cfg.serverUrl = "https://x";
        store.save(cfg);
        assertTrue(Files.exists(p));
    }

    @Test
    public void save_setsPosixFilePerms() throws IOException {
        assumeTrue("posix-only", isPosix());
        Path p = tmp.getRoot().toPath().resolve("config.json");
        ConfigStore store = new ConfigStore(p);
        McpConfig cfg = new McpConfig();
        cfg.serverUrl = "https://x";
        store.save(cfg);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
        // 必须 600
        assertTrue("must be owner-readable: " + perms, perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue("must be owner-writable: " + perms, perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse("must NOT be group-readable: " + perms, perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse("must NOT be other-readable: " + perms, perms.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    public void mcpConfig_isExpired_logic() {
        McpConfig cfg = new McpConfig();
        // loginAt 为 null → 已过期
        assertTrue(cfg.isExpired(0L));
        // loginAt 距 now 不超过 24h → 未过期
        cfg.loginAt = 1000L * McpConfig.LOGIN_TTL_MS; // 任意远点
        assertTrue(cfg.isExpired(cfg.loginAt + McpConfig.LOGIN_TTL_MS));
        assertFalse(cfg.isExpired(cfg.loginAt + McpConfig.LOGIN_TTL_MS - 1));
        assertTrue(cfg.isExpired(cfg.loginAt + McpConfig.LOGIN_TTL_MS + 1));
    }

    @Test
    public void mcpConfig_isAuthenticated_logic() {
        McpConfig cfg = new McpConfig();
        cfg.serverUrl = "https://docs.example.com";
        cfg.authType = McpConfig.AUTH_TYPE_COOKIE;
        // 没 cookie → false
        assertFalse(cfg.isAuthenticated());
        cfg.cookieAuthToken = "abc";
        assertTrue(cfg.isAuthenticated());

        // JWT 模式
        cfg = new McpConfig();
        cfg.serverUrl = "https://docs.example.com";
        cfg.authType = McpConfig.AUTH_TYPE_CASDOOR_JWT;
        assertFalse(cfg.isAuthenticated());
        cfg.token = "eyJ...";
        assertTrue(cfg.isAuthenticated());

        // 未知 authType
        cfg = new McpConfig();
        cfg.serverUrl = "https://x";
        cfg.authType = "weird";
        assertFalse(cfg.isAuthenticated());

        // 没 serverUrl
        cfg = new McpConfig();
        cfg.authType = McpConfig.AUTH_TYPE_COOKIE;
        cfg.cookieAuthToken = "x";
        assertFalse(cfg.isAuthenticated());
    }

    private static boolean isPosix() {
        try {
            return java.nio.file.FileSystems.getDefault()
                    .supportedFileAttributeViews().contains("posix");
        } catch (Throwable t) {
            return false;
        }
    }
}

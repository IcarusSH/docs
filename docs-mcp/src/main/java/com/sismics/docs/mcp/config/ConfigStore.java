package com.sismics.docs.mcp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 读写 {@code ~/.teedy-mcp/config.json}。
 *
 * <p>特点：</p>
 * <ul>
 *   <li><b>原子写</b>：先写 {@code config.json.tmp}，再 {@code ATOMIC_MOVE} 替换。
 *       防止半写状态导致下次启动解析失败</li>
 *   <li><b>权限收敛</b>：目录 700、文件 600（仅 Unix；Windows 跳过）</li>
 *   <li><b>损坏恢复</b>：读出 JSON 异常时返回 {@code null}，调用方走"首次登录"流程</li>
 *   <li><b>路径可注入</b>：构造参数传 {@code Path}，便于单测用 {@code @TempDir}</li>
 * </ul>
 *
 * <p>默认路径用 {@code user.home} 拼，不走 XDG，避免 macOS / Linux / WSL 行为分歧。</p>
 */
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    /** 子目录名（在用户 home 下） */
    public static final String DIR_NAME = ".teedy-mcp";

    /** 配置文件名 */
    public static final String FILE_NAME = "config.json";

    /**
     * Jackson 单例。
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES=false}：兼容未来 docs-mcp 版本新增的
     * config 字段，旧版客户端读到新字段时不要整个解析失败（应保留能识别的部分）。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path configFile;
    private final Path configDir;

    /**
     * 用默认路径 {@code ~/.teedy-mcp/config.json} 构造。
     */
    public ConfigStore() {
        this(Paths.get(System.getProperty("user.home"), DIR_NAME, FILE_NAME));
    }

    /**
     * 用注入路径构造（测试用）。
     */
    public ConfigStore(Path configFile) {
        this.configFile = configFile;
        this.configDir = configFile.getParent();
    }

    /**
     * 读取配置。文件不存在或损坏时返回 {@code null}。
     */
    public McpConfig load() throws IOException {
        if (!Files.exists(configFile)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(configFile)) {
            return MAPPER.readValue(in, McpConfig.class);
        } catch (Exception e) {
            log.warn("Failed to read {} ({}), treating as missing — user must re-login",
                    configFile, e.getMessage());
            return null;
        }
    }

    /**
     * 保存配置。原子写 + 自动 chmod 600（仅 Unix）。
     */
    public void save(McpConfig config) throws IOException {
        if (configDir == null) {
            throw new IOException("configDir is null (configFile must have a parent)");
        }
        Files.createDirectories(configDir);
        applyDirectoryPermissions(configDir);

        Path tmp = configDir.resolve(FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, config);
        } catch (IOException e) {
            // 清理失败的 tmp 文件
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // ignore
            }
            throw e;
        }

        try {
            Files.move(tmp, configFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 在某些文件系统（如 WSL 早期版本、网络挂载）不支持 ATOMIC_MOVE
            log.debug("ATOMIC_MOVE not supported, falling back to regular move");
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
        }

        applyFilePermissions(configFile);
    }

    /**
     * 删除配置（{@code zdedms logout} 用）。
     */
    public boolean delete() throws IOException {
        return Files.deleteIfExists(configFile);
    }

    public Path getConfigFile() {
        return configFile;
    }

    public Path getConfigDir() {
        return configDir;
    }

    // ====== POSIX 权限 ======

    private static void applyDirectoryPermissions(Path dir) {
        if (!isPosix()) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(dir, perms);
        } catch (IOException e) {
            log.debug("Failed to set posix perms 700 on {}: {}", dir, e.getMessage());
        }
    }

    private static void applyFilePermissions(Path file) {
        if (!isPosix()) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException e) {
            log.debug("Failed to set posix perms 600 on {}: {}", file, e.getMessage());
        }
    }

    private static boolean isPosix() {
        try {
            return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (Throwable t) {
            return false;
        }
    }
}

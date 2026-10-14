package com.sismics.docs.mcp.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI/MCP server entry point. PR1 placeholder; full Picocli subcommands land in PR6
 * and the MCP stdio server lands in PR5. For now this just prints the version and
 * exits so the shaded fat-jar has a runnable main class.
 *
 * <p>Logging goes to <strong>stderr</strong> (via slf4j-simple) so stdout stays clean
 * for the MCP JSON-RPC stream once the stdio server is wired up.</p>
 */
public final class CliMain {

    private static final Logger log = LoggerFactory.getLogger(CliMain.class);

    public static final String VERSION = "1.12-SNAPSHOT";

    private CliMain() {
        // utility class
    }

    public static void main(String[] args) {
        // PR1: only --version / no-args is recognized. Real subcommands come in PR6.
        if (args.length == 0 || isVersion(args)) {
            System.out.println("zdedms " + VERSION);
            System.out.println("docs-mcp module skeleton (PR1) — subcommands not yet implemented");
            return;
        }
        log.error("Unknown arguments: {}", String.join(" ", args));
        log.error("PR1 is a skeleton only. Full CLI lands in PR6.");
        System.exit(1);
    }

    private static boolean isVersion(String[] args) {
        for (String a : args) {
            if ("--version".equals(a) || "-V".equals(a) || "version".equals(a)) {
                return true;
            }
        }
        return false;
    }
}

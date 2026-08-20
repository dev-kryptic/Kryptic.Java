package dev.kryptic;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Kryptic daemon client for Java. During development startup, {@link #inject()} fetches the
 * current project's secrets from the local Kryptic daemon and exposes them as system
 * properties (the JVM cannot modify its own process environment). Outside development
 * it is a no-op, and it never throws - a missing daemon means the application simply
 * starts with the configuration it already has.
 *
 * <p>Spring Boot integration ({@code @EnableKryptic} feeding the Spring Environment)
 * builds on {@link #fetch()} and ships as a separate module.
 *
 * <p>Protocol: daemon/PROTOCOL.md v1 (newline-delimited JSON over a local socket -
 * a unix domain socket on macOS/Linux, a named pipe on Windows).
 */
public final class Kryptic {

    private static final int PROTOCOL_VERSION = 1;

    private Kryptic() {
    }

    /** What {@link #inject()} did. */
    public record Result(int injected, boolean skipped, String reason) {
    }

    /**
     * Fetches the secrets and sets each as a system property (existing properties and
     * process environment variables are never overwritten).
     */
    public static Result inject() {
        Map<String, String> secrets;

        String skipReason = skipReason();
        if (skipReason != null) return new Result(0, true, skipReason);

        try {
            secrets = fetchInternal();
        } catch (KrypticUnavailableException e) {
            warn(e.getMessage());
            return new Result(0, true, e.reason);
        }

        int injected = 0;
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            String key = secret.getKey();
            if (System.getProperty(key) != null || System.getenv(key) != null) continue;
            System.setProperty(key, secret.getValue());
            injected++;
        }
        return new Result(injected, false, null);
    }

    /**
     * Fetches the secrets as a map without touching system properties - the hook for
     * framework integrations. Returns an empty map on any problem (after one warning).
     */
    public static Map<String, String> fetch() {
        if (skipReason() != null) return Map.of();
        try {
            return fetchInternal();
        } catch (KrypticUnavailableException e) {
            warn(e.getMessage());
            return Map.of();
        }
    }

    // ---------- internals ----------

    private static final class KrypticUnavailableException extends Exception {
        final String reason;

        KrypticUnavailableException(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private static Map<String, String> fetchInternal() throws KrypticUnavailableException {
        Map<String, Object> config = findKrypticJson();

        String projectId = env("KRYPTIC_PROJECT_ID");
        if (projectId == null && config != null) projectId = (String) config.get("projectId");
        if (projectId == null)
            throw new KrypticUnavailableException("no_project",
                "no kryptic.json found (and no KRYPTIC_PROJECT_ID set) - nothing to inject.");

        String environment = env("KRYPTIC_ENV");
        if (environment == null && config != null) environment = (String) config.get("defaultEnvironment");
        if (environment == null) environment = "development";

        Map<String, Object> response;
        try {
            response = roundTrip(MiniJson.writeObject(new LinkedHashMap<>(Map.of(
                "v", PROTOCOL_VERSION, "type", "secrets", "projectId", projectId, "environment", environment))));
        } catch (IOException | RuntimeException e) {
            throw new KrypticUnavailableException("daemon_unreachable",
                "daemon not reachable (" + e.getMessage() + ") - continuing without injected secrets.");
        }

        if (!Boolean.TRUE.equals(response.get("ok"))) {
            String error = response.get("error") instanceof String s ? s : "internal";
            throw new KrypticUnavailableException(error,
                "daemon refused the request (" + error + "): " + response.getOrDefault("message", ""));
        }

        Map<String, String> secrets = new LinkedHashMap<>();
        if (response.get("secrets") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> secret
                    && secret.get("key") instanceof String key
                    && secret.get("value") instanceof String value) {
                    secrets.put(key, value);
                }
            }
        }
        return secrets;
    }

    private static String skipReason() {
        if ("true".equals(env("KRYPTIC_DISABLED"))) return "disabled";

        // Spring's convention first, then the generic ones.
        for (String variable : new String[] {"SPRING_PROFILES_ACTIVE", "APP_ENV", "ENVIRONMENT", "ENV"}) {
            String value = env(variable);
            if (value == null) continue;
            String lower = value.toLowerCase();
            if (lower.contains("production") || lower.equals("prod") || lower.contains("staging"))
                return variable.toLowerCase() + "_" + lower;
        }
        return null;
    }

    private static String socketPath() {
        String override = env("KRYPTIC_SOCKET_PATH");
        if (override != null) return override;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "\\\\.\\pipe\\kryptic-daemon";
        if (os.contains("linux")) {
            String runtimeDir = env("XDG_RUNTIME_DIR");
            if (runtimeDir != null) return Path.of(runtimeDir, "kryptic-daemon.sock").toString();
        }
        return "/tmp/kryptic-daemon.sock";
    }

    private static long timeoutMs() {
        String raw = env("KRYPTIC_TIMEOUT_MS");
        if (raw != null) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 2000;
    }

    private static Map<String, Object> roundTrip(String request) throws IOException {
        String path = socketPath();
        String line = path.startsWith("\\\\.\\pipe\\")
            ? roundTripNamedPipe(path, request)
            : roundTripUnixSocket(path, request);
        return MiniJson.parseObject(line);
    }

    private static String roundTripUnixSocket(String path, String request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(Path.of(path)));
            channel.write(ByteBuffer.wrap((request + "\n").getBytes(StandardCharsets.UTF_8)));

            StringBuilder received = new StringBuilder();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (received.indexOf("\n") < 0) {
                buffer.clear();
                if (channel.read(buffer) < 0) throw new IOException("connection closed");
                buffer.flip();
                received.append(StandardCharsets.UTF_8.decode(buffer));
            }
            return received.substring(0, received.indexOf("\n"));
        }
    }

    /**
     * Round trip over a Windows named pipe. The daemon serves a byte-mode pipe, so a
     * plain file handle works - no JNI and no dependency. KRYPTIC_TIMEOUT_MS covers
     * connecting (the pipe can briefly report "busy" between served clients); the read
     * then blocks until the daemon replies, which it does immediately or not at all -
     * matching the .NET client's semantics.
     */
    private static String roundTripNamedPipe(String path, String request) throws IOException {
        long deadline = System.nanoTime() + timeoutMs() * 1_000_000L;
        RandomAccessFile pipe = null;
        while (pipe == null) {
            try {
                pipe = new RandomAccessFile(path, "rw");
            } catch (FileNotFoundException e) {
                if (System.nanoTime() >= deadline)
                    throw new IOException("timed out connecting to the daemon pipe");
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while connecting to the daemon pipe");
                }
            }
        }

        try {
            pipe.write((request + "\n").getBytes(StandardCharsets.UTF_8));

            StringBuilder received = new StringBuilder();
            byte[] buffer = new byte[8192];
            while (received.indexOf("\n") < 0) {
                int read = pipe.read(buffer);
                if (read < 0) throw new IOException("connection closed");
                received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return received.substring(0, received.indexOf("\n"));
        } finally {
            pipe.close();
        }
    }

    /** Walks up from the working directory looking for kryptic.json. */
    private static Map<String, Object> findKrypticJson() {
        // Resolve via user.dir explicitly - java.nio caches the JVM-start directory otherwise.
        Path directory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("kryptic.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return MiniJson.parseObject(Files.readString(candidate));
                } catch (IOException | RuntimeException e) {
                    warn("could not parse " + candidate + " - ignoring it.");
                    return null;
                }
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static String env(String name) {
        // System property fallback lets tests (and unusual setups) override without env access.
        String property = System.getProperty(name);
        return property != null ? property : System.getenv(name);
    }

    private static void warn(String message) {
        if ("true".equals(env("KRYPTIC_SILENT"))) return;
        System.err.println("[kryptic] " + message);
    }
}

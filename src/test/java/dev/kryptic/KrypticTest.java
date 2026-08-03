package dev.kryptic;

// Tests against a mock daemon: a unix-socket server speaking PROTOCOL.md v1.
// Configuration goes through system properties (the SDK checks them before env vars).

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KrypticTest {

    private Path projectDir;
    private Path socketDir;
    private String originalUserDir;
    private ServerSocketChannel server;

    @BeforeEach
    void setUp() throws IOException {
        projectDir = Files.createTempDirectory("kryptic-sdk");
        Files.writeString(projectDir.resolve("kryptic.json"), "{\"projectId\":\"proj_test123456\"}");
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", projectDir.toString());

        for (String name : new String[] {"KRYPTIC_DISABLED", "KRYPTIC_PROJECT_ID", "KRYPTIC_ENV",
                "SPRING_PROFILES_ACTIVE", "INJECTED_KEY", "EXISTING_KEY", "KRYPTIC_SOCKET_PATH"}) {
            System.clearProperty(name);
        }
        System.setProperty("KRYPTIC_SILENT", "true");
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.dir", originalUserDir);
        if (server != null) server.close();
        System.clearProperty("INJECTED_KEY");
        System.clearProperty("EXISTING_KEY");
        System.clearProperty("KRYPTIC_SOCKET_PATH");
    }

    private void startMockDaemon(Function<String, String> handler) throws IOException {
        // Unix socket paths are length-capped — keep them short under /tmp.
        socketDir = Files.createTempDirectory(Path.of("/tmp"), "kd");
        Path socket = socketDir.resolve("d.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        System.setProperty("KRYPTIC_SOCKET_PATH", socket.toString());

        Thread thread = new Thread(() -> {
            try {
                while (true) {
                    SocketChannel connection = server.accept();
                    StringBuilder received = new StringBuilder();
                    ByteBuffer buffer = ByteBuffer.allocate(8192);
                    while (received.indexOf("\n") < 0 && connection.read(buffer) >= 0) {
                        buffer.flip();
                        received.append(StandardCharsets.UTF_8.decode(buffer));
                        buffer.clear();
                    }
                    String response = handler.apply(received.substring(0, received.indexOf("\n")));
                    connection.write(ByteBuffer.wrap((response + "\n").getBytes(StandardCharsets.UTF_8)));
                    connection.close();
                }
            } catch (IOException ignored) {
                // server closed — test over
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @Test
    void injectsSecretsAsSystemProperties() throws IOException {
        startMockDaemon(request -> {
            assertTrue(request.contains("\"projectId\":\"proj_test123456\""), request);
            assertTrue(request.contains("\"environment\":\"development\""), request);
            return "{\"v\":1,\"ok\":true,\"secrets\":[{\"key\":\"INJECTED_KEY\",\"value\":\"from-daemon\"}]}";
        });

        Kryptic.Result result = Kryptic.inject();

        assertFalse(result.skipped());
        assertEquals(1, result.injected());
        assertEquals("from-daemon", System.getProperty("INJECTED_KEY"));
    }

    @Test
    void neverOverwritesExistingProperties() throws IOException {
        System.setProperty("EXISTING_KEY", "real-env-wins");
        startMockDaemon(request ->
            "{\"v\":1,\"ok\":true,\"secrets\":[{\"key\":\"EXISTING_KEY\",\"value\":\"x\"}]}");

        Kryptic.Result result = Kryptic.inject();

        assertEquals(0, result.injected());
        assertEquals("real-env-wins", System.getProperty("EXISTING_KEY"));
    }

    @Test
    void noopWhenDaemonMissing() {
        System.setProperty("KRYPTIC_SOCKET_PATH", projectDir.resolve("missing.sock").toString());

        Kryptic.Result result = Kryptic.inject();

        assertTrue(result.skipped());
        assertEquals("daemon_unreachable", result.reason());
    }

    @Test
    void noopInProduction() {
        System.setProperty("SPRING_PROFILES_ACTIVE", "production");

        Kryptic.Result result = Kryptic.inject();

        assertTrue(result.skipped());
        assertEquals("spring_profiles_active_production", result.reason());
        System.clearProperty("SPRING_PROFILES_ACTIVE");
    }

    @Test
    void noopWhenDisabled() {
        System.setProperty("KRYPTIC_DISABLED", "true");

        Kryptic.Result result = Kryptic.inject();

        assertTrue(result.skipped());
        assertEquals("disabled", result.reason());
        System.clearProperty("KRYPTIC_DISABLED");
    }

    @Test
    void handlesErrorResponses() throws IOException {
        startMockDaemon(request -> "{\"v\":1,\"ok\":false,\"error\":\"access_denied\"}");

        Kryptic.Result result = Kryptic.inject();

        assertTrue(result.skipped());
        assertEquals("access_denied", result.reason());
    }

    @Test
    void overridesWin() throws IOException {
        System.setProperty("KRYPTIC_PROJECT_ID", "proj_override0001");
        System.setProperty("KRYPTIC_ENV", "staging");
        StringBuilder seen = new StringBuilder();
        startMockDaemon(request -> {
            seen.append(request);
            return "{\"v\":1,\"ok\":true,\"secrets\":[]}";
        });

        Kryptic.inject();

        assertTrue(seen.toString().contains("proj_override0001"), seen.toString());
        assertTrue(seen.toString().contains("staging"), seen.toString());
    }

    @Test
    void fetchReturnsMapWithoutTouchingProperties() throws IOException {
        startMockDaemon(request ->
            "{\"v\":1,\"ok\":true,\"secrets\":[{\"key\":\"FETCHED_KEY\",\"value\":\"v1\"}]}");

        Map<String, String> secrets = Kryptic.fetch();

        assertEquals(Map.of("FETCHED_KEY", "v1"), secrets);
        assertNull(System.getProperty("FETCHED_KEY"));
    }
}

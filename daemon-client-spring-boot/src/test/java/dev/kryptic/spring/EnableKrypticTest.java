package dev.kryptic.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class EnableKrypticTest {

    private Path projectDir;
    private String originalUserDir;
    private ServerSocketChannel server;

    @BeforeEach
    void setUp() throws IOException {
        projectDir = Files.createTempDirectory("kryptic-spring");
        Files.writeString(projectDir.resolve("kryptic.json"), "{\"projectId\":\"proj_test123456\"}");
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", projectDir.toString());

        for (String name : new String[] {"KRYPTIC_DISABLED", "KRYPTIC_PROJECT_ID", "KRYPTIC_ENV",
                "SPRING_PROFILES_ACTIVE", "INJECTED_KEY", "EXISTING_KEY", "KRYPTIC_SOCKET_PATH",
                "KRYPTIC_TIMEOUT_MS"}) {
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
        System.clearProperty("KRYPTIC_TIMEOUT_MS");
        System.clearProperty("SPRING_PROFILES_ACTIVE");
        System.clearProperty("KRYPTIC_DISABLED");
    }

    @Test
    void enableKrypticAddsFetchedSecrets() throws IOException {
        startMockDaemon(request ->
            "{\"v\":1,\"ok\":true,\"secrets\":[{\"key\":\"INJECTED_KEY\",\"value\":\"from-daemon\"}]}");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(App.class)) {
            assertEquals("from-daemon", context.getEnvironment().getProperty("INJECTED_KEY"));
            assertTrue(context.getEnvironment().getPropertySources().contains(KrypticEnvironment.PROPERTY_SOURCE_NAME));
        }
    }

    @Test
    void neverOverwritesExistingProperties() throws IOException {
        startMockDaemon(request ->
            "{\"v\":1,\"ok\":true,\"secrets\":[{\"key\":\"EXISTING_KEY\",\"value\":\"from-daemon\"}]}");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("existing", Map.of("EXISTING_KEY", "real-env-wins")));
        context.register(App.class);
        context.refresh();
        try {
            assertEquals("real-env-wins", context.getEnvironment().getProperty("EXISTING_KEY"));
            assertFalse(context.getEnvironment().getPropertySources().contains(KrypticEnvironment.PROPERTY_SOURCE_NAME));
        } finally {
            context.close();
        }
    }

    @Test
    void noopWhenDaemonMissing() {
        System.setProperty("KRYPTIC_SOCKET_PATH", projectDir.resolve("missing.sock").toString());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(App.class)) {
            assertNull(context.getEnvironment().getProperty("INJECTED_KEY"));
            assertFalse(context.getEnvironment().getPropertySources().contains(KrypticEnvironment.PROPERTY_SOURCE_NAME));
        }
    }

    @Test
    void noopInProduction() {
        System.setProperty("SPRING_PROFILES_ACTIVE", "production");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(App.class)) {
            assertFalse(context.getEnvironment().getPropertySources().contains(KrypticEnvironment.PROPERTY_SOURCE_NAME));
        }
    }

    private void startMockDaemon(Function<String, String> handler) throws IOException {
        Path socketDir = Files.createTempDirectory(Path.of("/tmp"), "kd");
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
                // server closed - test over
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @EnableKryptic
    @Configuration
    static class App {
    }
}

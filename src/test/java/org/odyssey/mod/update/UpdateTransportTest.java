package org.odyssey.mod.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class UpdateTransportTest {
    @TempDir Path root;

    @Test void streamsBoundedDownloadsFollowsTrustedRedirectsAndRemovesPartialFiles() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jar = new byte[128 * 1024];
        java.util.Arrays.fill(jar, (byte) 42);
        server.createContext("/jar", exchange -> {
            exchange.sendResponseHeaders(200, jar.length);
            try (var output = exchange.getResponseBody()) { output.write(jar); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/jar");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/escape", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://localhost:1/untrusted");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var transport = new UpdateTransport(HttpClient.newHttpClient(),
                    uri -> uri.getAuthority().equals(base.getAuthority()), Duration.ofSeconds(5));
            Path target = root.resolve("download.jar");
            transport.download(base.resolve("/redirect"), target, jar.length);
            assertArrayEquals(jar, Files.readAllBytes(target));
            assertThrows(Exception.class, () -> transport.download(base.resolve("/jar"), target, 64));
            assertFalse(Files.exists(target));
            assertThrows(Exception.class, () -> transport.download(base.resolve("/jar"), target, jar.length + 1));
            assertFalse(Files.exists(target));
            assertThrows(Exception.class, () -> transport.bytes(base.resolve("/escape"), 100));
            assertThrows(Exception.class, () -> new UpdateTransport().bytes(base.resolve("/jar"), 100));
            assertThrows(Exception.class, () -> transport.bytes(base.resolve("/missing"), 100));
        } finally { server.stop(0); }
    }

    @Test void stalledResponseBodyHasATotalDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try (var output = exchange.getResponseBody()) {
                output.write(1); output.flush();
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stall");
            var transport = new UpdateTransport(HttpClient.newHttpClient(), candidate -> candidate.equals(uri),
                    Duration.ofMillis(300));
            Path target = root.resolve("stalled.jar");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(Exception.class, () -> transport.download(uri, target, 100)));
            assertFalse(Files.exists(target));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}

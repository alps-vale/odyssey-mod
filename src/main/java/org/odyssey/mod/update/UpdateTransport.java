package org.odyssey.mod.update;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** No API token, bounded redirects, response sizes and total request time. */
public final class UpdateTransport {
    private static final Set<String> HOSTS = Set.of("github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com");
    private final HttpClient client;
    private final Predicate<URI> trusted;
    private final Duration deadline;

    public UpdateTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                uri -> "https".equals(uri.getScheme()) && HOSTS.contains(uri.getHost())
                        && uri.getUserInfo() == null && uri.getPort() == -1, Duration.ofSeconds(60));
    }

    UpdateTransport(HttpClient client, Predicate<URI> trusted, Duration deadline) {
        this.client = client; this.trusted = trusted; this.deadline = deadline;
    }

    public byte[] bytes(URI uri, int maximum) throws Exception {
        var output = new ByteArrayOutputStream();
        receive(uri, output, maximum);
        return output.toByteArray();
    }

    public void download(URI uri, Path destination, long expectedSize) throws Exception {
        if (Files.isSymbolicLink(destination)) throw new IOException("Unsafe download path");
        try (var output = Files.newOutputStream(destination)) {
            long received = receive(uri, output, expectedSize);
            if (received != expectedSize) throw new IOException("Incomplete release download");
        } catch (Exception failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }

    private long receive(URI uri, OutputStream output, long maximum) throws Exception {
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (!trusted.test(uri))
                throw new IOException("Untrusted release endpoint");
            var sink = new LimitedBody(output, maximum);
            var request = HttpRequest.newBuilder(uri).timeout(deadline)
                    .header("User-Agent", "Odyssey-Updater").GET().build();
            var responseFuture = client.sendAsync(request, info -> {
                if (info.statusCode() != 200) return HttpResponse.BodySubscribers.replacing(0L);
                return sink;
            });
            HttpResponse<Long> response;
            try { response = responseFuture.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
            finally { responseFuture.cancel(true); sink.cancel(); }
            int status = response.statusCode();
            if (status == 200) return response.body();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                throw new IOException("Release download unavailable (HTTP " + status + ")");
            uri = uri.resolve(response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Missing redirect location")));
        }
        throw new IOException("Too many release redirects");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<Long> {
        private final OutputStream output;
        private final long maximum;
        private final CompletableFuture<Long> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        LimitedBody(OutputStream output, long maximum) { this.output = output; this.maximum = maximum; }
        @Override public CompletionStage<Long> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            try {
                for (var buffer : buffers) {
                    int size = buffer.remaining();
                    if (size > maximum - received) throw new IOException("Oversized release response");
                    byte[] bytes = new byte[Math.min(size, 16_384)];
                    while (buffer.hasRemaining()) {
                        int count = Math.min(buffer.remaining(), bytes.length);
                        buffer.get(bytes, 0, count);
                        output.write(bytes, 0, count);
                        received += count;
                    }
                }
                subscription.request(1);
            } catch (Exception error) { onError(error); subscription.cancel(); }
        }
        @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public synchronized void onComplete() { result.complete(received); }
        synchronized void cancel() {
            if (subscription != null) subscription.cancel();
            if (!result.isDone()) result.completeExceptionally(new IOException("Release request cancelled"));
        }
    }
}

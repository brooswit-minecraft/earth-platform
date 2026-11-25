package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP range reads for the globe's streamed rasters: bounded in concurrency, retried, shared by
 * every caller waiting on the same piece (single flight). Their disk caches are written through
 * {@link DiskCache#write(java.nio.file.Path, byte[])}.
 */
final class RangeFiles implements AutoCloseable {
    interface Load<T> { T get() throws IOException, InterruptedException; }

    private final String what, agent;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore transfers;
    private final ExecutorService executor;
    final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), failures = new AtomicLong();

    /** @param what what is read, for messages ("Land cover"); {@code agent} names the dataset to servers */
    RangeFiles(String what, String agent, String threadName, int maxConcurrentTransfers, Duration timeout) {
        this.what = what;
        this.agent = agent;
        this.timeout = timeout;
        this.transfers = new Semaphore(maxConcurrentTransfers);
        this.executor = Executors.newFixedThreadPool(maxConcurrentTransfers, NamedThreads.daemon(threadName));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
    }

    /** The bytes of a file from offset, or null when the file does not exist. */
    byte[] fetch(URI uri, long offset, int length) throws IOException, InterruptedException {
        DataWaits.before();
        try { return transfer(uri, offset, length); }
        finally { DataWaits.after(); }
    }

    private byte[] transfer(URI uri, long offset, int length) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "earth-platform/" + agent)
                .header("Range", "bytes=" + offset + "-" + (offset + length - 1)).GET().build();
        IOException last = null;
        // Three tries for ordinary failures; a host asking us to slow down is waited out a few more times.
        for (int attempt = 0, slowed = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(250L << attempt);
            HostBackoff.await(uri);
            HostBackoff.reachable(uri, what);
            transfers.acquire();
            try {
                // Asked again: a host can go down while this transfer waited its turn.
                HostBackoff.reachable(uri, what);
                long start = System.nanoTime();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 500 && !HostBackoff.slowDown(status)) HostBackoff.failed(uri, what, "HTTP " + status);
                else HostBackoff.answered(uri);
                if (status == 206 && !matchesRequestedRange(response, offset, length)) {
                    last = new IOException("HTTP 206 for " + uri + " with a Content-Range that doesn't match the requested bytes="
                            + offset + "-" + (offset + length - 1) + " - " + response.headers().firstValue("Content-Range").orElse("(missing)"));
                } else if (status == 206 || status == 200) {
                    downloads.incrementAndGet();
                    byte[] body = response.body();
                    Downloads.count(what, body.length, System.nanoTime() - start);
                    if (status == 200 && (offset != 0 || body.length > length)) body = Arrays.copyOfRange(body, (int) offset, (int) Math.min(body.length, offset + length));
                    return body;
                } else if (status == 404 || status == 403) return null;
                else last = new IOException("HTTP " + status + " for " + uri);
                if (HostBackoff.slowDown(status) && ++slowed < HostBackoff.ATTEMPTS) {
                    HostBackoff.pause(uri, response, slowed, what);
                    attempt--;
                }
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException failure) {
                last = failure;
                HostBackoff.failed(uri, what, failure);
            } finally {
                transfers.release();
            }
        }
        throw new MissingTerrainException(what + " download failed after retries: " + uri + " (" + last + ")");
    }

    /**
     * Whether a 206 response's own Content-Range header - never just its body length, matching
     * length alone still passes a response at the wrong offset - actually covers the requested
     * bytes=offset-(offset+length-1): same start, and a body exactly as long as the header's own
     * end-start+1 (which can legitimately be shorter than length at the file's end, but never
     * mismatched with what Content-Range itself claims). A missing or unparseable header fails
     * this - a proxy that mislabels a mismatched or truncated reply 206 must not be trusted
     * just because the status code says success.
     */
    private static boolean matchesRequestedRange(HttpResponse<byte[]> response, long offset, int length) {
        String header = response.headers().firstValue("Content-Range").orElse(null);
        if (header == null || !header.startsWith("bytes ")) return false;
        int dash = header.indexOf('-', 6), slash = header.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) return false;
        try {
            long start = Long.parseLong(header.substring(6, dash));
            long end = Long.parseLong(header.substring(dash + 1, slash));
            return start == offset && response.body().length == end - start + 1;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    /**
     * Runs loads all at once, each on its own virtual thread, so as many transfers run together as
     * the transfer limit allows (a parallel stream would stop at the processor count). Waits for
     * all of them, then rethrows the first failure.
     */
    static void all(java.util.List<Runnable> loads) {
        RuntimeException first = null;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<Future<?>> running = new java.util.ArrayList<>(loads.size());
            for (Runnable load : loads) running.add(threads.submit(load));
            for (Future<?> load : running) {
                try { DataWaits.get(load); }
                catch (ExecutionException failed) {
                    if (first == null) first = failed.getCause() instanceof RuntimeException runtime ? runtime : new MissingTerrainException(String.valueOf(failed.getCause()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new MissingTerrainException("Interrupted while prefetching");
                }
            }
        }
        if (first != null) throw first;
    }

    /** Loads a piece once however many callers want it at the same time; a failure is retried by the next caller. */
    <K, T> T singleFlight(Map<K, CompletableFuture<T>> flights, K key, Load<T> load, String piece) {
        TickShare.giveWay();
        CompletableFuture<T> mine = new CompletableFuture<>(), shared = flights.putIfAbsent(key, mine);
        if (shared == null) {
            try { mine.complete(load.get()); }
            catch (Throwable failure) { failures.incrementAndGet(); mine.completeExceptionally(failure); }
            finally { flights.remove(key, mine); }
            shared = mine;
        }
        return await(shared, piece);
    }

    <T> T await(CompletableFuture<T> future, String piece) {
        try {
            return DataWaits.get(future, timeout.toMillis() * 4, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted waiting for " + piece);
        } catch (TimeoutException late) {
            throw new MissingTerrainException("Timed out waiting for " + piece);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof MissingTerrainException missing) throw missing;
            throw new MissingTerrainException(piece + " failed: " + failed.getCause());
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}

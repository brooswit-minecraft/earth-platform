package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A 206 must be verified against its own Content-Range header, not trusted on status and length
 * alone - John's 55866/55870/55874 finding on MINECRAFT-580/571: a proxy that mislabels a wrong-
 * offset or truncated reply 206 (even with a matching byte count) must not be forwarded as success.
 */
class RangeFilesTest {
    @Test
    void acceptsA206WhoseContentRangeMatchesTheRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] chunk = "0123456789".getBytes();
            exchange.getResponseHeaders().add("Content-Range", "bytes 10-19/100");
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("0123456789".getBytes(), body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsA206WithAWrongOffsetContentRangeEvenWithAMatchingByteCount() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            byte[] chunk = "WRONGOFFST".getBytes();
            // First two attempts: a mismatched proxy answering 10 bytes at the wrong offset (20-29
            // instead of the requested 10-19) - same length, wrong Content-Range. Third attempt: fixed.
            if (n <= 2) exchange.getResponseHeaders().add("Content-Range", "bytes 20-29/100");
            else { chunk = "0123456789".getBytes(); exchange.getResponseHeaders().add("Content-Range", "bytes 10-19/100"); }
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("0123456789".getBytes(), body, "the wrong-offset 206 must be retried, not forwarded as success");
            assertEquals(3, requests.get(), "exactly the two rejected attempts plus the one that succeeded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsA206WithAMissingContentRangeHeader() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            byte[] chunk = "0123456789".getBytes();
            if (n > 1) exchange.getResponseHeaders().add("Content-Range", "bytes 10-19/100");
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("0123456789".getBytes(), body);
            assertEquals(2, requests.get(), "the missing-header 206 must be retried, not forwarded as success");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAShortMidFileReplyThatIsNotProvenToBeEndOfFile() throws Exception {
        // A proxy that truncates mid-file: 5 bytes at the right offset, same count as a legitimate
        // EOF trim, but the numeric total (100) proves bytes 15-19 still exist - so this is a real
        // truncation bug, not EOF, and must be retried rather than silently accepted.
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            byte[] chunk = n <= 2 ? "01234".getBytes() : "0123456789".getBytes();
            exchange.getResponseHeaders().add("Content-Range", n <= 2 ? "bytes 10-14/100" : "bytes 10-19/100");
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("0123456789".getBytes(), body, "the unproven short mid-file 206 must be retried, not forwarded as success");
            assertEquals(3, requests.get(), "exactly the two rejected attempts plus the one that succeeded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnOversizedContentRangeThatExtendsPastWhatWasRequested() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            byte[] chunk = n <= 2 ? "0123456789TOOMUCH".getBytes() : "0123456789".getBytes();
            exchange.getResponseHeaders().add("Content-Range", n <= 2 ? "bytes 10-26/100" : "bytes 10-19/100");
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("0123456789".getBytes(), body, "the oversized 206 must be retried, not forwarded as success");
            assertEquals(3, requests.get(), "exactly the two rejected attempts plus the one that succeeded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsAnExactLengthReplyWithAnUnknownTotal() throws Exception {
        // Current proxies sometimes answer an exact-length (non-EOF-trimmed) reply with "*" as the
        // total - end == requestedEnd already proves it covers what was asked, so an unknown total
        // must not sink an otherwise-correct response.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] chunk = "0123456789".getBytes();
            exchange.getResponseHeaders().add("Content-Range", "bytes 10-19/*");
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("0123456789".getBytes(), body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsAShorterThanRequestedBodyAtEndOfFileWhenContentRangeAgrees() throws Exception {
        // A legitimate partial-at-EOF reply: fewer bytes than requested, but the body length matches
        // what Content-Range itself claims (10-14, five bytes) - must not be confused with a
        // truncated or mismatched reply.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] chunk = "01234".getBytes();
            exchange.getResponseHeaders().add("Content-Range", "bytes 10-14/15");
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try (RangeFiles files = new RangeFiles("Test", "test-agent", "range-files-test", 2, Duration.ofSeconds(5))) {
            byte[] body = files.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 10, 10);
            assertArrayEquals("01234".getBytes(), body);
        } finally {
            server.stop(0);
        }
    }
}

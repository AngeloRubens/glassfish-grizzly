package org.glassfish.grizzly.http2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.memory.MemoryProbe;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.junit.Test;

/** Diagnostic only: heap pool use per response, HTTP/2 vs HTTP/1.1. */
public class PoolDiagTest extends AbstractHttp2Test {
    private static final int N = 500;

    @Test
    public void diag() throws Exception {
        final StringBuilder report = new StringBuilder("POOL-DIAG");
        for (final boolean h2 : new boolean[] { false, true }) {
            final AtomicLong allocated = new AtomicLong(), allocatedCount = new AtomicLong(), fromPool = new AtomicLong(), released = new AtomicLong();
            final int port = TestUtils.findAvailableTcpPort();
            final HttpServer httpServer = createServer(null, port, false, false);
            final MemoryManager mm = httpServer.getListener("grizzly").getTransport().getMemoryManager();
            final MemoryProbe probe = new MemoryProbe.Adapter() {
                @Override
                public void onBufferAllocateEvent(final int size) {
                    allocated.addAndGet(size);
                    allocatedCount.incrementAndGet();
                }

                @Override
                public void onBufferAllocateFromPoolEvent(final int size) {
                    fromPool.addAndGet(size);
                }

                @Override
                public void onBufferReleaseToPoolEvent(final int size) {
                    released.addAndGet(size);
                }
            };
            httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
                @Override
                public void service(final Request request, final Response response) throws Exception {
                    response.setContentType("text/plain");
                    response.setContentLength(13);
                    response.getOutputStream().write("Hello, World!".getBytes(StandardCharsets.US_ASCII));
                }
            }, "/");
            httpServer.start();
            try {
                if (h2) {
                    final LinkedBlockingQueue<Boolean> completed = new LinkedBlockingQueue<>();
                    final Connection<?> connection = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport())
                            .processor(createClientFilterChainAsBuilder(false, true, new BaseFilter() {
                                @Override
                                public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                                    final HttpContent content = ctx.getMessage();
                                    if (content.isLast()) {
                                        completed.add(Boolean.TRUE);
                                    }
                                    return ctx.getStopAction();
                                }
                            }).build())
                            .build().connect("localhost", port).get(10, TimeUnit.SECONDS);
                    try {
                        for (int i = 0; i < N + 100; i++) {
                            if (i == 100) {
                                mm.getMonitoringConfig().addProbes(probe);
                            }
                            final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_2_0)
                                    .host("localhost:" + port).build();
                            connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());
                            completed.poll(10, TimeUnit.SECONDS);
                        }
                    } finally {
                        connection.closeSilently();
                    }
                } else {
                    try (Socket socket = new Socket("127.0.0.1", port)) {
                        socket.setSoTimeout(10_000);
                        final OutputStream out = socket.getOutputStream();
                        final InputStream in = socket.getInputStream();
                        for (int i = 0; i < N + 100; i++) {
                            if (i == 100) {
                                mm.getMonitoringConfig().addProbes(probe);
                            }
                            out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            final StringBuilder head = new StringBuilder();
                            while (head.indexOf("\r\n\r\n") < 0) {
                                head.append((char) in.read());
                            }
                            in.readNBytes(13);
                        }
                    }
                }
            } finally {
                mm.getMonitoringConfig().removeProbes(probe);
                httpServer.shutdownNow();
            }
            report.append(h2 ? " h2:" : " h1:").append(" allocatedBytes/resp=").append(allocated.get() / N).append(" allocations=")
                    .append(allocatedCount.get()).append(" fromPool/resp=").append(fromPool.get() / N).append(" releasedToPool/resp=")
                    .append(released.get() / N);
        }
        org.junit.Assert.fail(report.toString());
    }
}

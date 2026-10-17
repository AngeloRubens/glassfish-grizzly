/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.HttpTrailer;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.junit.Test;

/**
 * HTTP/2 requests are created on the selector thread and recycled on a worker thread; they are given back to the
 * selector thread, so the streams of a connection reuse them.
 */
public class Http2RequestReuseTest extends AbstractHttp2Test {

    private static final int REQUESTS = 50;

    @Test
    public void http2RequestsAreReusedWhenServiceRunsOnAWorker() throws Exception {
        final Set<Object> packets = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> serviceThreads = Collections.synchronizedSet(new TreeSet<>());
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer httpServer = createServer(null, port, false, false);
        httpServer.getListener("grizzly").getTransport()
                .setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (packets) {
                    packets.add(request.getRequest());
                }
                serviceThreads.add(Thread.currentThread().getName());
                response.setContentType("text/plain");
                response.setContentLength(2);
                response.getWriter().write("ok");
            }
        }, "/");
        httpServer.start();
        try {
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
                // one stream after the other on the same connection
                for (int i = 0; i < REQUESTS; i++) {
                    final HttpRequestPacket request = HttpRequestPacket.builder().method(Method.GET).uri("/").protocol(Protocol.HTTP_2_0)
                            .host("localhost:" + port).build();
                    connection.write(HttpContent.builder(request).content(Buffers.EMPTY_BUFFER).last(true).build());
                    assertTrue("response " + i, completed.poll(10, TimeUnit.SECONDS) != null);
                }
            } finally {
                connection.closeSilently();
            }
        } finally {
            httpServer.shutdownNow();
        }
        assertEquals("service() on one worker thread: " + serviceThreads, 1, serviceThreads.size());
        assertTrue("service() on a worker, not on the selector: " + serviceThreads, !serviceThreads.iterator().next().contains("kernel"));
        // a few more if the next stream came in before the worker had recycled the last request
        assertTrue("distinct HTTP/2 requests for " + REQUESTS + " streams: " + packets.size(), packets.size() <= REQUESTS / 5);
    }

    /**
     * A request is not reused while its stream may still receive frames: the late trailers of a stream whose response
     * was sent without reading the body must not reach the request of the next stream.
     */
    @Test
    public void lateTrailersDoNotReachTheNextStreamsRequest() throws Exception {
        final AtomicReference<String> injected = new AtomicReference<>("not served");
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch trailersSent = new CountDownLatch(1);
        final int port = TestUtils.findAvailableTcpPort();
        final HttpServer httpServer = createServer(null, port, false, false);
        httpServer.getListener("grizzly").getTransport()
                .setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                if (request.getRequestURI().equals("/second")) {
                    secondStarted.countDown();
                    // the first stream's trailers arrive while this request is in use
                    trailersSent.await(10, TimeUnit.SECONDS);
                    Thread.sleep(300);
                    injected.set(String.valueOf(request.getHeader("x-injected")));
                }
                // the first stream is answered without reading its body
                response.setContentType("text/plain");
                response.setContentLength(2);
                response.getWriter().write("ok");
            }
        }, "/");
        httpServer.start();
        try {
            final LinkedBlockingQueue<Object> completed = new LinkedBlockingQueue<>();
            final Connection<?> connection = TCPNIOConnectorHandler.builder(httpServer.getListener("grizzly").getTransport())
                    .processor(createClientFilterChainAsBuilder(false, true, new BaseFilter() {
                        @Override
                        public NextAction handleRead(final FilterChainContext ctx) throws IOException {
                            final HttpContent content = ctx.getMessage();
                            if (content.isLast()) {
                                completed.add(((HttpResponsePacket) content.getHttpHeader()).getRequest());
                            }
                            return ctx.getStopAction();
                        }
                    }).build())
                    .build().connect("localhost", port).get(10, TimeUnit.SECONDS);
            try {
                final HttpRequestPacket first = post(port, "/first");
                connection.write(HttpContent.builder(first).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "a")).last(false).build());
                assertTrue("first response", completed.poll(10, TimeUnit.SECONDS) == first);
                // the worker has recycled the first request; its stream is still open for input
                Thread.sleep(300);

                final HttpRequestPacket second = post(port, "/second");
                connection.write(HttpContent.builder(second).content(Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, "b")).last(false).build());
                assertTrue("second request in service", secondStarted.await(10, TimeUnit.SECONDS));

                connection.write(HttpTrailer.builder(first).content(Buffers.EMPTY_BUFFER).last(true).header("x-injected", "1").build());
                trailersSent.countDown();
                assertTrue("second response", completed.poll(10, TimeUnit.SECONDS) == second);
            } finally {
                connection.closeSilently();
            }
        } finally {
            httpServer.shutdownNow();
        }
        assertEquals("x-injected seen by the second stream's request", "null", injected.get());
    }

    private static HttpRequestPacket post(final int port, final String uri) {
        return HttpRequestPacket.builder().method(Method.POST).uri(uri).protocol(Protocol.HTTP_2_0).host("localhost:" + port).build();
    }
}

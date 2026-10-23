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

package org.glassfish.grizzly.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.threadpool.VirtualThreadExecutorService;
import org.junit.Test;

/**
 * The give-back of recycled request objects to the thread that created them must also hold when the service runs on
 * virtual threads: the objects are created on one virtual thread, recycled on that one or, after a suspend, on another,
 * and a virtual thread's cache ends with it. Whatever thread recycles an object, no response may be mixed up with another
 * request, and no recycled object keeps a reference to a cache owner.
 */
public class VirtualThreadRequestReuseTest {

    private static final int CONNECTIONS = 16;
    private static final int REQUESTS = 25;

    @Test
    public void synchronousAndSuspendedRequestsOnVirtualThreads() throws Exception {
        final Set<Object> objects = Collections.newSetFromMap(new IdentityHashMap<>());
        final ExecutorService resumer = Executors.newVirtualThreadPerTaskExecutor();
        final VirtualThreadExecutorService workers = VirtualThreadExecutorService.createInstance();
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("virtual", "127.0.0.1", 0);
        server.addListener(listener);
        listener.getTransport().setWorkerThreadPool(workers);
        listener.registerAddOn((networkListener, builder) -> builder.add(builder.indexOfType(HttpServerFilter.class), new BaseFilter() {
            @Override
            public NextAction handleRead(final FilterChainContext ctx) {
                synchronized (objects) {
                    objects.add(ctx);
                }
                return ctx.getInvokeAction();
            }
        }));
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                synchronized (objects) {
                    objects.add(request);
                    objects.add(request.getRequest());
                }
                // the response echoes the request line, so a request object mixed up with another one shows
                final byte[] body = request.getRequestURI().getBytes(StandardCharsets.US_ASCII);
                response.setContentLength(body.length);
                if (request.getRequestURI().startsWith("/suspend")) {
                    response.suspend();
                    resumer.execute(() -> {
                        try {
                            response.getOutputStream().write(body);
                        } catch (final java.io.IOException e) {
                            throw new IllegalStateException(e);
                        } finally {
                            response.resume();
                        }
                    });
                } else {
                    response.getOutputStream().write(body);
                }
            }
        }, "/");
        server.start();
        final ExecutorService clients = Executors.newFixedThreadPool(CONNECTIONS);
        try {
            final List<Future<?>> done = new ArrayList<>();
            for (int c = 0; c < CONNECTIONS; c++) {
                final int connection = c;
                done.add(clients.submit(() -> {
                    try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
                        socket.setSoTimeout(20_000);
                        final OutputStream out = socket.getOutputStream();
                        final InputStream in = socket.getInputStream();
                        for (int i = 0; i < REQUESTS; i++) {
                            final String uri = (i % 2 == 0 ? "/sync" : "/suspend") + "-" + connection + "-" + i;
                            out.write(("GET " + uri + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            final StringBuilder head = new StringBuilder();
                            while (head.indexOf("\r\n\r\n") < 0) {
                                final int b = in.read();
                                assertTrue("end of stream after:\n" + head, b >= 0);
                                head.append((char) b);
                            }
                            assertTrue(head.toString(), head.toString().startsWith("HTTP/1.1 200"));
                            assertEquals(uri, new String(in.readNBytes(uri.length()), StandardCharsets.US_ASCII));
                        }
                        return null;
                    }
                }));
            }
            for (final Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            clients.shutdownNow();
            server.shutdownNow();
            resumer.shutdownNow();
            workers.shutdownNow();
        }
        synchronized (objects) {
            RequestReuseTest.awaitNoOwner(objects);
        }
    }
}

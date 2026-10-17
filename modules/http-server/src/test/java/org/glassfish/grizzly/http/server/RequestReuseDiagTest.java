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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.junit.Test;

/** Diagnostic only: how many request packet objects serve 50 keep-alive requests on one worker thread. */
public class RequestReuseDiagTest {

    @Test
    public void count() throws Exception {
        for (final String mode : new String[] { "stream", "writer", "writer-length", "stream-chunked" }) {
            final Set<Object> packets = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<Object> requests = Collections.newSetFromMap(new IdentityHashMap<>());
            final HttpServer server = new HttpServer();
            final NetworkListener listener = new NetworkListener("diag", "127.0.0.1", 0);
            server.addListener(listener);
            listener.getTransport().setWorkerThreadPoolConfig(ThreadPoolConfig.defaultConfig().copy().setCorePoolSize(1).setMaxPoolSize(1));
            server.getServerConfiguration().addHttpHandler(new HttpHandler() {
                @Override
                public void service(final Request request, final Response response) throws Exception {
                    synchronized (packets) {
                        packets.add(request.getRequest());
                        requests.add(request);
                    }
                    final byte[] body = "hello".getBytes(StandardCharsets.US_ASCII);
                    switch (mode) {
                    case "stream":
                        response.setContentLength(body.length);
                        response.getOutputStream().write(body);
                        break;
                    case "stream-chunked":
                        response.getOutputStream().write(body);
                        break;
                    case "writer":
                        response.getWriter().write("hello");
                        break;
                    default:
                        response.setContentLength(body.length);
                        response.getWriter().write("hello");
                    }
                }
            }, "/");
            server.start();
            try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
                socket.setSoTimeout(10_000);
                final OutputStream out = socket.getOutputStream();
                final InputStream in = socket.getInputStream();
                for (int i = 0; i < 50; i++) {
                    out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    readResponse(in);
                }
            } finally {
                server.shutdownNow();
            }
            System.out.println("REUSE-DIAG mode=" + mode + " distinct packets=" + packets.size() + " distinct requests=" + requests.size());
        }
    }

    /** Reads one response: headers, then a Content-Length body or chunks up to the last one. */
    private static void readResponse(final InputStream in) throws Exception {
        final StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            head.append((char) in.read());
        }
        final String h = head.toString().toLowerCase();
        final int cl = h.indexOf("content-length: ");
        if (cl >= 0) {
            final int len = Integer.parseInt(h.substring(cl + 16, h.indexOf("\r\n", cl)).trim());
            in.readNBytes(len);
            return;
        }
        while (true) {
            final StringBuilder line = new StringBuilder();
            while (!line.toString().endsWith("\r\n")) {
                line.append((char) in.read());
            }
            final int size = Integer.parseInt(line.toString().trim(), 16);
            in.readNBytes(size + 2);
            if (size == 0) {
                return;
            }
        }
    }
}

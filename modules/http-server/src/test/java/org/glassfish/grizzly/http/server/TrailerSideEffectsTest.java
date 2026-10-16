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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Trailer fields of a chunked request must not act like the header fields of the same name: they arrive after the
 * request has been dispatched, and Expect is even a forbidden trailer field.
 */
public class TrailerSideEffectsTest {

    private HttpServer httpServer;
    private int port;
    private final Queue<String> seen = new ConcurrentLinkedQueue<>();

    @Before
    public void before() throws Exception {
        port = TestUtils.findAvailableTcpPort();
        httpServer = HttpServer.createSimpleServer(null, port);
        httpServer.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                final byte[] body = request.getInputStream().readAllBytes();
                seen.add(request.getRequestURI() + " upgrade=" + request.getRequest().isUpgrade() + " expect="
                        + request.getRequest().requiresAcknowledgement());
                response.setContentType("text/plain");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
        }, "/");
        httpServer.start();
    }

    @After
    public void after() {
        httpServer.shutdownNow();
    }

    @Test
    public void upgradeTrailerDoesNotCloseTheConnection() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /first HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "3\r\nabc\r\n0\r\nUpgrade: websocket\r\n\r\n"
                    + "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            final InputStream in = socket.getInputStream();
            assertEquals("abc", readResponseBody(in));
            assertEquals("", readResponseBody(in));
        }
    }

    @Test
    public void expectTrailerDoesNotRequireAcknowledgement() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /first HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "3\r\nabc\r\n0\r\nExpect: 100-continue\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            final InputStream in = socket.getInputStream();
            assertEquals("abc", readResponseBody(in));
            // the trailer was dropped as a forbidden one, so it must not have touched the request either
            assertEquals("/first upgrade=false expect=false", seen.poll());
        }
    }

    private static String readResponseBody(final InputStream in) throws IOException {
        final String status = readLine(in);
        assertTrue(status, status.startsWith("HTTP/1.1 200"));
        int contentLength = -1;
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            final int colon = line.indexOf(':');
            if (line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        assertTrue("missing Content-Length", contentLength >= 0);
        return new String(in.readNBytes(contentLength), StandardCharsets.US_ASCII);
    }

    private static String readLine(final InputStream in) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\n') {
            if (b == -1) {
                throw new IOException("unexpected end of stream after: " + line);
            }
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII).trim();
    }
}

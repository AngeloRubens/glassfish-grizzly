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

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Responses carry a Date header (RFC 9110, 6.6.1: an origin server with a clock must send one).
 */
public class DateHeaderTest {

    private static final String OWN_DATE = "Thu, 01 Jan 1970 00:00:00 GMT";

    /** Two responses on one keep-alive connection, then one whose Date the application set itself. */
    @Test
    public void responsesCarryADateHeader() throws Exception {
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("date", "127.0.0.1", 0);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                if (request.getRequestURI().equals("/own")) {
                    response.setHeader("Date", OWN_DATE);
                }
                response.getWriter().write("hello");
            }
        }, "/");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();
            for (int i = 0; i < 2; i++) {
                final String head = exchange(out, in, "/", false);
                assertEquals("Date headers in response " + i + ":\n" + head, 1, count(head, "\r\ndate: "));
            }
            final String own = exchange(out, in, "/own", true);
            assertEquals("Date headers:\n" + own, 1, count(own, "\r\ndate: "));
            assertTrue(own, own.contains("\r\ndate: " + OWN_DATE.toLowerCase() + "\r\n"));
        } finally {
            server.shutdownNow();
        }
    }

    /** Sends a GET and returns the lower-cased head of the response, after reading its body. */
    private static String exchange(final OutputStream out, final InputStream in, final String uri, final boolean close) throws Exception {
        out.write(("GET " + uri + " HTTP/1.1\r\nHost: localhost\r\n" + (close ? "Connection: close\r\n" : "") + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        final StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            final int b = in.read();
            if (b < 0) {
                throw new EOFException(head.toString());
            }
            head.append((char) b);
        }
        final String lower = head.toString().toLowerCase();
        final int cl = lower.indexOf("\r\ncontent-length: ");
        assertTrue("no Content-Length in:\n" + head, cl >= 0);
        final int length = Integer.parseInt(lower.substring(cl + 18, lower.indexOf("\r\n", cl + 2)).trim());
        assertEquals(length, in.readNBytes(length).length);
        return lower;
    }

    private static int count(final String text, final String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}

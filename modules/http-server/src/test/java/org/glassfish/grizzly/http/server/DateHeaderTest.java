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

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Responses carry a Date header (RFC 9110, 6.6.1: an origin server with a clock must send one).
 */
public class DateHeaderTest {

    @Test
    public void responsesCarryADateHeader() throws Exception {
        final HttpServer server = new HttpServer();
        final NetworkListener listener = new NetworkListener("date", "127.0.0.1", 0);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                response.getWriter().write("hello");
            }
        }, "/");
        server.start();
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {
            socket.setSoTimeout(10_000);
            final OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            final InputStream in = socket.getInputStream();
            final ByteArrayOutputStream response = new ByteArrayOutputStream();
            in.transferTo(response);
            final String text = response.toString(StandardCharsets.ISO_8859_1);
            final String head = text.substring(0, text.indexOf("\r\n\r\n") + 2).toLowerCase();
            assertTrue("no Date header in:\n" + text, head.contains("\r\ndate: "));
        } finally {
            server.shutdownNow();
        }
    }
}

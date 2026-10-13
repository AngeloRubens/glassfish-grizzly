/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.glassfish.grizzly.perf;

import java.nio.charset.StandardCharsets;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

/**
 * Minimal plaintext/JSON server used as the load-test target in CI (TechEmpower-style endpoints).
 * Usage: {@code java -cp benchmarks.jar org.glassfish.grizzly.perf.HelloServer [port]}
 */
public final class HelloServer {

    private static final byte[] HELLO = "Hello, World!".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JSON = "{\"message\":\"Hello, World!\"}".getBytes(StandardCharsets.US_ASCII);

    public static void main(final String[] args) throws Exception {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        final HttpServer server = HttpServer.createSimpleServer(null, "0.0.0.0", port);
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(final Request request, final Response response) throws Exception {
                final boolean json = request.getRequestURI().startsWith("/json");
                final byte[] body = json ? JSON : HELLO;
                response.setContentType(json ? "application/json" : "text/plain");
                response.setHeader("Server", "grizzly");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
        }, "/");
        server.start();
        System.out.println("READY " + port);
        Thread.currentThread().join();
    }
}

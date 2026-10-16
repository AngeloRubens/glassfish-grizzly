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

package org.glassfish.grizzly.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Full HTTP/1.1 request header decode through {@link HttpServerFilter#handleRead(FilterChainContext)}: request line,
 * header names/values (with RFC 9110 strict validation, the default), known-header detection and a few typical
 * application lookups.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class HttpRequestDecodeBenchmark {

    static final String MINIMAL = "GET / HTTP/1.1\r\n"
            + "Host: localhost:8080\r\n"
            + "\r\n";

    static final String BROWSER = "GET /static/js/app.4f1c2a9b.bundle.js?v=20261013&lang=it HTTP/1.1\r\n"
            + "Host: www.example.com\r\n"
            + "Connection: keep-alive\r\n"
            + "sec-ch-ua: \"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"\r\n"
            + "sec-ch-ua-mobile: ?0\r\n"
            + "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36\r\n"
            + "sec-ch-ua-platform: \"Linux\"\r\n"
            + "Accept: */*\r\n"
            + "Sec-Fetch-Site: same-origin\r\n"
            + "Sec-Fetch-Mode: no-cors\r\n"
            + "Sec-Fetch-Dest: script\r\n"
            + "Referer: https://www.example.com/catalog/products?page=2&sort=price\r\n"
            + "Accept-Encoding: gzip, deflate, br, zstd\r\n"
            + "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7\r\n"
            + "Cookie: JSESSIONID=8F3A2B1C9D0E4F5A6B7C8D9E0F1A2B3C; _ga=GA1.1.123456789.1700000000; theme=dark; consent=yes\r\n"
            + "If-None-Match: \"5d8c72a5edda8d6a:0\"\r\n"
            + "\r\n";

    static final String API_POST = "POST /api/v1/orders HTTP/1.1\r\n"
            + "Host: api.example.com\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: 27\r\n"
            + "Accept: application/json\r\n"
            + "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U\r\n"
            + "X-Request-Id: 3f2504e0-4f89-11d3-9a0c-0305e82c3301\r\n"
            + "User-Agent: okhttp/4.12.0\r\n"
            + "\r\n"
            + "{\"sku\":\"A-1\",\"quantity\":2}";

    @Param({ "minimal", "browser", "api_post" })
    public String request;

    /**
     * heap: array-backed buffer (the byte[] parsing path); direct: a direct ByteBuffer; composite: two heap buffers
     * split in the middle of the header block, as when a request arrives in two reads (both take the Buffer path).
     */
    @Param({ "heap", "direct", "composite" })
    public String input;

    private ByteBuffer direct;

    private byte[] template;
    private HttpServerFilter filter;
    private StandaloneConnection connection;
    private MemoryManager mm;

    @Setup(Level.Trial)
    public void setup() {
        final String req = switch (request) {
            case "minimal" -> MINIMAL;
            case "browser" -> BROWSER;
            case "api_post" -> API_POST;
            default -> throw new IllegalArgumentException(request);
        };
        template = req.getBytes(StandardCharsets.ISO_8859_1);
        filter = new HttpServerFilter(true, 8192, null, null);
        connection = new StandaloneConnection();
        mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        direct = ByteBuffer.allocateDirect(template.length);
    }

    private Buffer inputBuffer() {
        switch (input) {
        case "direct": {
            direct.clear();
            direct.put(template).flip();
            return Buffers.wrap(mm, direct);
        }
        case "composite": {
            final int half = template.length / 2;
            return Buffers.appendBuffers(mm, Buffers.wrap(mm, Arrays.copyOfRange(template, 0, half)),
                    Buffers.wrap(mm, Arrays.copyOfRange(template, half, template.length)));
        }
        default:
            // the parser lower-cases header names in place, so every op works on a fresh copy
            return Buffers.wrap(mm, Arrays.copyOf(template, template.length));
        }
    }

    @Benchmark
    public Object decode(final Blackhole bh) throws IOException {
        final FilterChainContext ctx = FilterChainContext.create(connection);
        ctx.setMessage(inputBuffer());
        filter.handleRead(ctx);

        final HttpContent content = ctx.getMessage();
        final HttpRequestPacket req = (HttpRequestPacket) content.getHttpHeader();
        bh.consume(req.getRequestURI());
        bh.consume(req.getHeader(Header.Host));
        bh.consume(req.getHeader("user-agent"));
        bh.consume(req.getContentType());
        bh.consume(req.getHeader("x-forwarded-for")); // miss: scans all headers

        // start the next op with a fresh request, as after a completed keep-alive exchange; recycling returns the
        // request to the ThreadCache like org.glassfish.grizzly.http.server.Request#recycle does
        connection.getAttributes().removeAttribute(HttpServerFilter.HTTP_SERVER_REQUEST_ATTR_NAME);
        req.recycle();
        return content;
    }
}

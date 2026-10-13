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

import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.memory.MemoryManager;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Response header serialization ({@link HttpCodecFilter#encodeMimeHeaders}) for headers set by application code as
 * Strings, plus header lookup/set on {@link MimeHeaders} as done by the server before committing a response.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class HttpHeaderEncodeBenchmark {

    private MemoryManager mm;
    private MimeHeaders headers;
    private Buffer out;
    private byte[] temp;

    @Setup(Level.Trial)
    public void setup() {
        mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        headers = new MimeHeaders();
        headers.setValue("Content-Type").setString("text/html;charset=UTF-8");
        headers.setValue("Content-Language").setString("it-IT");
        headers.setValue("Cache-Control").setString("no-cache, no-store, must-revalidate");
        headers.setValue("ETag").setString("W/\"5d8c72a5edda8d6a:0\"");
        headers.setValue("Last-Modified").setString("Tue, 13 Oct 2026 10:15:30 GMT");
        headers.setValue("Strict-Transport-Security").setString("max-age=31536000; includeSubDomains");
        headers.setValue("X-Content-Type-Options").setString("nosniff");
        headers.setValue("Set-Cookie").setString("JSESSIONID=8F3A2B1C9D0E4F5A6B7C8D9E0F1A2B3C; Path=/; HttpOnly; Secure; SameSite=Lax");
        headers.setValue("Vary").setString("Accept-Encoding");
        out = mm.allocate(4096);
        temp = new byte[4096];
    }

    @Benchmark
    public Buffer encodeHeaders() {
        out.clear();
        return HttpCodecFilter.encodeMimeHeaders(mm, out, headers, temp, false);
    }

    @Benchmark
    public Object setAndLookup() {
        // what the server does around commit: overwrite known headers, probe for absent ones
        headers.setValue(Header.ContentType).setString("text/html;charset=UTF-8");
        headers.setValue("Vary").setString("Accept-Encoding");
        final Object a = headers.getValue(Header.TransferEncoding);
        final Object b = headers.getValue("content-encoding");
        return a == null ? b : a;
    }
}

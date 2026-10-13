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

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.attributes.AttributeHolder;
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
 * The per-request connection attribute traffic of an HTTP keep-alive exchange on the default (thread-safe) holder:
 * probe for an in-flight request (miss), set it, read it back a few times, read an attribute with an initializer
 * (idle record, present), then remove the request.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class AttributeHolderBenchmark {

    private static final AttributeBuilder BUILDER = Grizzly.DEFAULT_ATTRIBUTE_BUILDER;
    private static final Attribute<Object> REQUEST = BUILDER.createAttribute("perf.request");
    private static final Attribute<Object> SSL_CTX = BUILDER.createAttribute("perf.ssl");
    private static final Attribute<Object> IDLE = BUILDER.createAttribute("perf.idle", (Supplier<Object>) Object::new);

    private AttributeHolder holder;
    private final Object request = new Object();

    @Setup(Level.Trial)
    public void setup() {
        holder = BUILDER.createSafeAttributeHolder();
        IDLE.get(holder);
    }

    @Benchmark
    public Object exchange() {
        Object r = REQUEST.get(holder); // miss
        if (r == null) {
            REQUEST.set(holder, request);
        }
        final Object ssl = SSL_CTX.get(holder); // miss, plain connection
        r = REQUEST.get(holder);
        final Object idle = IDLE.get(holder);
        REQUEST.get(holder);
        REQUEST.remove(holder);
        return ssl == null ? idle : r;
    }
}

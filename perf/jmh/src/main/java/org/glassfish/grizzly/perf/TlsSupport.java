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

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.glassfish.grizzly.ssl.SSLEngineConfigurator;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

/**
 * Server-side TLS for the perf HelloServer: the JDK {@link SSLEngine}, or Netty's OpenSSL/BoringSSL-backed
 * {@link SSLEngine} (netty-tcnative) plugged into Grizzly's SSLFilter through an {@link SSLEngineConfigurator}.
 */
final class TlsSupport {

    private TlsSupport() {
    }

    static SSLEngineConfigurator engineConfigurator(final String provider, final String keyStore, final String password) throws Exception {
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keyStore)) {
            ks.load(in, password.toCharArray());
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());

        if ("openssl".equals(provider)) {
            final SslContext openSsl = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.OPENSSL).build();
            return new SSLEngineConfigurator(SSLContext.getDefault(), false, false, false) {
                @Override
                public SSLEngine createSSLEngine(final String peerHost, final int peerPort) {
                    // server-mode engine; OPENSSL (not OPENSSL_REFCNT) engines release native memory on their own
                    return openSsl.newEngine(ByteBufAllocator.DEFAULT);
                }
            };
        }

        final SSLContext jdk = SSLContext.getInstance("TLS");
        jdk.init(kmf.getKeyManagers(), null, null);
        return new SSLEngineConfigurator(jdk, false, false, false);
    }
}

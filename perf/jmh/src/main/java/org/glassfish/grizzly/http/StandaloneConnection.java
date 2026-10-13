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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;

/**
 * Socket-less connection used to drive the HTTP codec in-process (same approach as HttpRequestParseTest).
 */
final class StandaloneConnection extends NIOConnection {

    private final SocketAddress localAddress = new InetSocketAddress("127.0.0.1", 0);
    private final SocketAddress peerAddress = new InetSocketAddress("127.0.0.1", 0);

    StandaloneConnection() {
        super(TCPNIOTransportBuilder.newInstance().build());
    }

    @Override
    protected void preClose() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress getPeerAddress() {
        return peerAddress;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public int getReadBufferSize() {
        return 65536;
    }

    @Override
    public void setReadBufferSize(int readBufferSize) {
    }

    @Override
    public int getWriteBufferSize() {
        return 65536;
    }

    @Override
    public void setWriteBufferSize(int writeBufferSize) {
    }

    @Override
    public void notifyCanWrite(WriteHandler handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void notifyCanWrite(WriteHandler handler, int length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canWrite() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canWrite(int length) {
        throw new UnsupportedOperationException();
    }
}

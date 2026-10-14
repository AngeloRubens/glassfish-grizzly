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

package org.glassfish.grizzly.perf.netty;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AsciiString;

/**
 * Netty reference for the perf harness: /plaintext and /json like HelloServer, handled on the event loop, writes
 * flushed once per read batch (channelReadComplete). Usage: {@code java -jar netty-reference.jar nio|epoll|io_uring [port] [threads]}
 */
public final class HelloNetty {

    private static final byte[] HELLO = "Hello, World!".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JSON = "{\"message\":\"Hello, World!\"}".getBytes(StandardCharsets.US_ASCII);
    private static final AsciiString SERVER = AsciiString.cached("netty");
    private static final AsciiString TEXT_PLAIN = AsciiString.cached("text/plain");
    private static final AsciiString APPLICATION_JSON = AsciiString.cached("application/json");

    public static void main(final String[] args) throws Exception {
        final String transport = args.length > 0 ? args[0] : "nio";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        final int threads = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        final String tls = args.length > 3 ? args[3] : null; // jdk | openssl
        final SslContext sslContext = tls == null ? null : sslContext(tls);

        final IoHandlerFactory ioHandler;
        final Class<? extends ServerChannel> channelClass;
        switch (transport) {
            case "epoll" -> {
                Epoll.ensureAvailability();
                ioHandler = EpollIoHandler.newFactory();
                channelClass = EpollServerSocketChannel.class;
            }
            case "io_uring" -> {
                IoUring.ensureAvailability();
                ioHandler = IoUringIoHandler.newFactory();
                channelClass = IoUringServerSocketChannel.class;
            }
            default -> {
                ioHandler = NioIoHandler.newFactory();
                channelClass = NioServerSocketChannel.class;
            }
        }

        final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(threads, ioHandler);
        try {
            new ServerBootstrap()
                    .group(group)
                    .channel(channelClass)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            if (sslContext != null) {
                                ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                            }
                            ch.pipeline().addLast(new HttpServerCodec(), new HelloHandler());
                        }
                    })
                    .bind(port).sync();
            System.out.println("READY " + port + " transport=" + transport + " threads=" + threads + " tls=" + tls);
            Thread.currentThread().join();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static SslContext sslContext(final String provider) throws Exception {
        final char[] password = System.getProperty("perf.keystore.pass", "changeit").toCharArray();
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(System.getProperty("perf.keystore", "perf.p12"))) {
            ks.load(in, password);
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        return SslContextBuilder.forServer(kmf).sslProvider("openssl".equals(provider) ? SslProvider.OPENSSL : SslProvider.JDK).build();
    }

    private static final class HelloHandler extends SimpleChannelInboundHandler<Object> {

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) {
            if (!(msg instanceof HttpRequest request)) {
                return; // LastHttpContent of a GET
            }
            final boolean json = request.uri().startsWith("/json");
            final byte[] body = json ? JSON : HELLO;
            final ByteBuf content = Unpooled.wrappedBuffer(body);
            final FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, json ? APPLICATION_JSON : TEXT_PLAIN)
                    .set(HttpHeaderNames.SERVER, SERVER)
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            if (HttpUtil.isKeepAlive(request)) {
                ctx.write(response);
            } else {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.write(response).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            ctx.close();
        }
    }
}

/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.transport.http.netty.util.client.websocket;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.util.TestUtil;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * WebSocket client class for test
 */
public class WebSocketTestClient {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTestClient.class);

    private String url = String.format("ws://%s:%d/%s", TestUtil.TEST_HOST, TestUtil.SERVER_CONNECTOR_PORT, "test");
    private final String subProtocol;
    private Map<String, String> customHeaders;
    private boolean handshakeSuccessful;

    private Channel channel;
    private WebSocketTestClientFrameHandler handler;

    public WebSocketTestClient() {
        this(null, new HashMap<>());
    }

    public WebSocketTestClient(String url) {
        this(null, new HashMap<>());
        this.url = url;
    }

    public WebSocketTestClient(String subProtocol, Map<String, String> customHeaders) {
        this.subProtocol = subProtocol;
        this.customHeaders = customHeaders;
    }

    public void setCountDownLatch(CountDownLatch countDownLatch) {
        if (!handshakeSuccessful) {
            throw new IllegalStateException("Cannot set countdown latch until handshake is done");
        }
        handler.setCountDownLatch(countDownLatch);
    }

    public WebSocketClientHandshaker getHandshaker() {
        return handler.getHandshaker();
    }

    public HttpResponse getHandshakeResponse() {
        return handler.getHttpResponse();
    }

    /**
     * Handshake with WebSocket server.
     *
     * @throws URISyntaxException throws if there is an error in the URI syntax.
     * @throws InterruptedException throws if the connecting the server is interrupted.
     */
    public void handshake() throws URISyntaxException, InterruptedException {
        URI uri = new URI(url);
        EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
        HttpHeaders headers = new DefaultHttpHeaders();
        customHeaders.forEach(headers::add);
        try {
            WebSocketClientHandshaker clientHandshaker = WebSocketClientHandshakerFactory
                    .newHandshaker(uri, WebSocketVersion.V13, subProtocol, true, headers);
            handler = new WebSocketTestClientFrameHandler(clientHandshaker);

            Bootstrap clientBootstrap = new Bootstrap();
            clientBootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpClientCodec(),
                                      new HttpObjectAggregator(8192),
                                      WebSocketClientCompressionHandler.INSTANCE,
                                      handler);
                        }
                    });
            channel = clientBootstrap.connect(uri.getHost(), uri.getPort()).sync().channel();
            handshakeSuccessful = handler.handshakeFuture().sync().isSuccess();
            logger.debug("WebSocket Handshake successful : " + handshakeSuccessful);
        } catch (Exception e) {
            logger.error("Handshake unsuccessful : " + e.getMessage());
            throw e;
        }
    }

    /**
     * Send text to the server.
     * @param text text need to be sent.
     */
    public void sendText(String text) throws InterruptedException {
        if (channel == null) {
            logger.error("Channel is null. Cannot send text.");
            throw new IllegalArgumentException("Cannot find the channel to write");
        }
        channel.writeAndFlush(new TextWebSocketFrame(text)).sync();
    }

    /**
     * Send binary data to server.
     * @param buf buffer containing the data need to be sent.
     */
    public void sendBinary(ByteBuffer buf) throws InterruptedException {
        if (channel == null) {
            logger.error("Channel is null. Cannot send binary frame.");
            throw new IllegalArgumentException("Cannot find the channel to write");
        }
        channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(buf))).sync();
    }

    /**
     * Send a ping message to the server.
     * @param buf content of the ping message to be sent.
     */
    public void sendPing(ByteBuffer buf) throws InterruptedException {
        if (channel == null) {
            logger.error("Channel is null. Cannot send ping.");
            throw new IllegalArgumentException("Cannot find the channel to write");
        }
        channel.writeAndFlush(new PingWebSocketFrame(Unpooled.wrappedBuffer(buf))).sync();
    }

    /**
     * Send close frame to the remote backend.
     *
     * @param statusCode Status code to close the connection.
     * @param reason Reason to close the connection.
     * @return this {@link WebSocketTestClient}.
     * @throws InterruptedException if connection is interrupted while sending the message.
     */
    public WebSocketTestClient sendCloseFrame(int statusCode, String reason) throws InterruptedException {
        if (channel == null) {
            logger.error("Channel is null. Cannot send text.");
            throw new IllegalArgumentException("Cannot find the channel to write");
        }
        channel.writeAndFlush(new CloseWebSocketFrame(statusCode, reason)).sync();
        return this;
    }

    /**
     * @return the text received from the server.
     */
    public String getTextReceived() {
        return handler.getTextReceived();
    }

    /**
     * @return the binary data received from the server.
     */
    public ByteBuffer getBufferReceived() {
        return handler.getBufferReceived();
    }

    /**
     * Check whether the connection is still open or not.
     *
     * @return true if connection is still open.
     */
    public boolean isOpen() {
       return channel.isOpen();
    }

    /**
     * Check whether a ping is received.
     *
     * @return true if a ping is received.
     */
    public boolean isPingReceived() {
        return handler.isPingReceived();
    }

    /**
     * Check whether a ping is received.
     *
     * @return true if a ping is received.
     */
    public boolean isPongReceived() {
        return handler.isPongReceived();
    }

    /**
     * Retrieve received close frame.
     *
     * @return received close frame.
     */
    public CloseWebSocketFrame getReceivedCloseFrame() {
        return handler.getReceiveCloseFrame();
    }

    /**
     * Forcefully shutdown WebSocket client.
     *
     * @throws InterruptedException if the connection is interrupted when closing channel.
     */
    public void closeChannel() throws InterruptedException {
        if (channel.isOpen()) {
            channel.close().sync();
        }
    }
}

/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            //获取ServerSocketChannel的config
            final ChannelConfig config = config();
            //获取ServerSocketChannel的pipeline
            final ChannelPipeline pipeline = pipeline();

            // 获得 RecvByteBufAllocator.Handle 对象
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            // 重置 RecvByteBufAllocator.Handle 对象
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        //accept一个channel放到readBuf中
                        int localRead = doReadMessages(readBuf);
                        // 无可读取的客户端的连接，结束
                        if (localRead == 0) {
                            break;
                        }
                        // 读取出错
                        if (localRead < 0) {
                            // 标记关闭
                            closed = true;
                            break;
                        }

                        // 读取消息数量 + localRead
                        allocHandle.incMessagesRead(localRead);
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    // 记录异常
                    exception = t;
                }

                int size = readBuf.size();
                // 循环 readBuf 数组，触发 Channel read 事件到 pipeline 中。
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    // 将事件传递到，我们在serverBootstrap#init方法中国注册的ServerBootstrapAcceptor，
                    // 将客户端的 Netty NioSocketChannel 注册到 EventLoop 上
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                // 清空 readBuf 数组
                readBuf.clear();
                // 读取完成
                allocHandle.readComplete();
                // 触发 Channel readComplete 事件到 pipeline 中。
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    //判断是否需要关闭
                    closed = closeOnReadError(exception);
                    //传递异常事件
                    pipeline.fireExceptionCaught(exception);
                }

                //如果需要关闭
                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    //如果成功读取到一个连接事件，且channel的autoRead不是true，取消对accept感兴趣事件
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}

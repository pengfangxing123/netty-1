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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * 基于换行来进行消息粘包拆包处理
 * 它会处理 "\n" 和 "\r\n" 两种换行符
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {


    /**
     * 一条消息的最大长度
     * Maximum length of a frame we're willing to decode.
     */
    private final int maxLength;
    /**
     * 是否快速失败
     *
     * 当 true 时，未找到消息，但是超过最大长度，则马上触发 Exception 到下一个节点
     * 当 false 时，未找到消息，但是超过最大长度，需要匹配到一条消息后，再触发 Exception 到下一个节点
     *
     * Whether or not to throw an exception as soon as we exceed maxLength.
     */
    private final boolean failFast;

    /**
     * 是否过滤掉换行分隔符。
     *
     * 如果为 true ，解码的消息不包含换行符。
     */
    private final boolean stripDelimiter;

    /**
     * 是否处于废弃模式
     *
     * 如果为 true ，说明解析超过最大长度( maxLength )，结果还是找不到换行符
     *
     * True if we're discarding input because we're already over maxLength.
     */
    private boolean discarding;

    /**
     * 废弃的字节数
     */
    private int discardedBytes;

    /**
     * 最后扫描的位置
     *
     * Last scan position.
     */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer);
        if (!discarding) {
            // 未处于废弃模式
            if (eol >= 0) {
                //找到了换行符(标识)
                final ByteBuf frame;
                //length就是消息的长度
                final int length = eol - buffer.readerIndex();

                // 分隔符的长度。2 为 `\r\n` ，1 为 `\n`
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                //如果消息超过了最大长度，
                if (length > maxLength) {
                    //丢弃消息(将读指针移到换行符之后)
                    buffer.readerIndex(eol + delimLength);
                    //抛出异常
                    fail(ctx, length);
                    return null;
                }

                //判断是否需要去掉分隔符
                if (stripDelimiter) {
                    //需要去掉，读取消息
                    frame = buffer.readRetainedSlice(length);
                    //读指针 跳过分隔符
                    buffer.skipBytes(delimLength);
                } else {
                    //读取消息和分隔符
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
                //没有找到分隔符
                final int length = buffer.readableBytes();
                if (length > maxLength) {
                    //如果消息已经超过了最大长度
                    //将长度赋值给discardedBytes，因为会丢弃这个消息，不记录的话，会导致废弃模式下fail的时候获取不到丢弃的长度
                    discardedBytes = length;
                    //丢弃全部的消息
                    buffer.readerIndex(buffer.writerIndex());
                    //修改废弃模式标识，进入废弃模式
                    discarding = true;
                    //offset置为0
                    offset = 0;
                    if (failFast) {
                        //如果是快速失败是true，那么直接抛出异常
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else {
            //废弃模式下(进入废弃模式的条件就是前面读取没有找到分隔符，消息长度已经超过允许的最大长度了)
            //判断是否找到分隔符
            if (eol >= 0) {
                //找到了分隔符
                //计算消息长度
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                //丢弃消息
                buffer.readerIndex(eol + delimLength);
                //重置丢弃消息的数量
                discardedBytes = 0;
                //退出废弃模式
                discarding = false;
                //如果不快速失败，这里要触发异常
                if (!failFast) {
                    fail(ctx, length);
                }
            } else {
                //如果还没找就继续记录长度，继续丢弃消息
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        //寻找换行符所在的位置
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            //找到了，将偏移量重置为0
            offset = 0;
            // 如果前一个字节位 `\r` ，说明找到的是 `\n` ，所以需要 -1 ，因为寻找的是首个换行符的位置
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            //更新偏移量，表示offset之前都没有换行符，下次查找将从读指针+offset的位置开始找，因为该位置之前的已经找过了，没有
            offset = totalLength;
        }
        return i;
    }
}

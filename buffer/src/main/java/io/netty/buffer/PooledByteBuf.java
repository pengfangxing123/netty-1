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

package io.netty.buffer;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;


abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /**
     * Recycler 处理器，用于回收对象
     */
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

    /**
     * Chunk 对象
     */
    protected PoolChunk<T> chunk;

    /**
     * 从 Chunk 对象中分配的内存块所处的位置
     */
    protected long handle;
    /**
     * 内存空间。具体什么样的数据，通过子类设置泛型。
     */
    protected T memory;
    /**
     * {@link #memory} 开始位置
     *
     * @see #idx(int)
     */
    protected int offset;
    /**
     * 容量
     *
     * @see #capacity()
     */
    protected int length;

    /**
     * 要注意的是，maxLength 属性，不是表示最大容量。maxCapacity 属性，才是真正表示最大容量。
     * 那么，maxLength 属性有什么用？表示占用 memory 的最大容量( 而不是 PooledByteBuf 对象的最大容量 )。
     * 在写入数据超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity
     */
    int maxLength;

    PoolThreadCache cache;

    /**
     * 临时 ByteBuff 对象
     *
     * @see #internalNioBuffer()
     */
    ByteBuffer tmpNioBuf;

    /**
     * ByteBuf 分配器对象
     */
    private ByteBufAllocator allocator;

    /**
     * 池化的 PooledByteBuf 刚new 出来时是没有分配内存的，得allocator调用了init方法后才完成可使用得内存分配
     * @param recyclerHandle
     * @param maxCapacity
     */
    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    /**
     * 基于 Poolooled 的 PoolChunk 对象
     * 池化的 PooledByteBuf 刚new 出来时是没有分配内存的，得allocator调用了init方法后才完成可使用得内存分配
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param offset
     * @param length
     * @param maxLength
     * @param cache
     */
    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    /**
     * 基于 unPoolooled 的 PoolChunk 对象调用
     * @param chunk
     * @param length
     */
    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     */
    final void reuse(int maxCapacity) {
        // 设置最大容量
        maxCapacity(maxCapacity);
        // 设置引用数量为 0(好像是2)
        resetRefCnt();
        // 重置读写索引为 0
        setIndex0(0, 0);
        // 重置读写标记位为 0
        discardMarks();
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public int maxFastWritableBytes() {
        return Math.min(maxLength, maxCapacity()) - writerIndex;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        // 校验新的容量，不能超过最大容量
        checkNewCapacity(newCapacity);

        // If the request capacity does not require reallocation, just update the length of the memory.
        if (chunk.unpooled) {
            // Chunk 内存，非池化
            // 相等，无需扩容 / 缩容
            if (newCapacity == length) {
                return this;
            }
        } else {
            // 扩容
            if (newCapacity > length) {
                //如果小于等于maxLength，
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            // 缩容
            } else if (newCapacity < length) {
                //新容量小于当前容量，但是大于 memory 最大容量的一半，有下面两种情况，无需进行缩容
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        //这里也不通过会缩容
                        if (newCapacity > maxLength - 16) {
                            //因为 Netty SubPage 大小是以16递增的。如果目前块的容量maxLength -16 小于目标容量newCapacity，
                            //就不满足容量要求了，所以不去调整内存分配信息
                            length = newCapacity;
                            // 设置读写索引，避免超过最大容量
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        //大于512也不重新调整内存分配信息
                        length = newCapacity;
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {
                // 相等，无需扩容 / 缩容
                return this;
            }
        }

        // Reallocation required.
        //扩容，缩容
        //非池化 newCapacity!=length时，为什么非池化用length判断，且不等于就扩容，缩容，因为initUnpooled（）length是等于maxLength的
        //池化 142行newCapacity <= maxLength判断不通过时；148行newCapacity <= maxLength >>> 1；151行newCapacity <= maxLength - 16时
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    /**
     * 全部复制，有底层数据Byte数组共享
     * 浅拷贝
     * @return
     */
    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    /**
     * 复制index~写指针的位置(index起的可读部分)有底层数据Byte数组共享
     * 浅拷贝
     * @return
     */
    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    /**
     * 当引用计数为 0 时，调用该方法，进行内存回收
     */
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    /**
     * 因为是大块里面的 内存 所以要加一个偏移量offset
     * @param index
     * @return
     */
    protected final int idx(int index) {
        return offset + index;
    }
}

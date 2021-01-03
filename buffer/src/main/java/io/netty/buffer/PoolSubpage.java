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

/**
 * Jemalloc 算法将每个 Arena 切分成多个小块 Chunk
 * 但是实际上，每个 Chunk 依然是相当大的内存块。
 * 因为在 Jemalloc 建议为 4MB ，Netty 默认使用为 16MB 。
 * 进一步提供提高内存分配效率并减少内存碎片，Jemalloc 算法将每个 Chunk 切分成多个小块 Page 。
 * 一个典型的切分是将 Chunk 切分为 2048 块 Page ，Netty 也是如此，因此 Page 的大小为：16MB / 2048 = 8KB
 * @param <T>
 */
final class PoolSubpage<T> implements PoolSubpageMetric {
    /**
     * 所属 PoolChunk 对象
     */
    final PoolChunk<T> chunk;
    /**
     * 在 {@link PoolChunk#memoryMap} 的节点编号
     * 当前page在chunk中的id
     */
    private final int memoryMapIdx;
    /**
     * 在 Chunk 中，偏移字节量
     * 在某个page中的偏移量
     *
     * @see PoolChunk#runOffset(int)
     */
    private final int runOffset;
    /**
     * Page 大小 {@link PoolChunk#pageSize}
     */
    private final int pageSize;
    /**
     * Subpage 分配信息数组
     *
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。
     * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
     *   例如：Page 默认大小为 8KB ，Subpage 默认最小为 16 B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
     *        因此，bitmap 数组大小为 512 / 64 = 8 。
     * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     *    为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
     */
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    /**
     * 是否未销毁
     */
    boolean doNotDestroy;
    /**
     * 每个 Subpage 的占用内存大小
     */
    int elemSize;
    /**
     * 总共 Subpage 的数量
     */
    private int maxNumElems;
    /**
     * {@link #bitmap} 长度
     * 标记真正使用的数组大小
     */
    private int bitmapLength;
    /**
     * 下一个可分配 Subpage 的数组位置
     */
    private int nextAvail;
    /**
     * 剩余可用 Subpage 的数量
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** 特殊的构造方法 创建双向链表，头节点
     * Special constructor that creates a linked list head
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        //初始化所属chunk的信息
        this.chunk = chunk;
        //chunk中page的节点编号
        this.memoryMapIdx = memoryMapIdx;
        //chunk中page的节点的偏移量
        this.runOffset = runOffset;
        //chunk中page的大小
        this.pageSize = pageSize;
        //根据page大小计算最多要多少个Long 来表示
        //一个long 64位，subPage最小是16
        //所以就是注释的pageSize / 16 / 64
        // pageSize / 16 / 64
        bitmap = new long[pageSize >>> 10];

        //初始化
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            //计算实际用到的长度，maxNumElems >>> 6=maxNumElems/64，因为long是64位
            bitmapLength = maxNumElems >>> 6;
            // 未整除，补 1
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            // 初始化 bitmap
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * 返回一个long类型
     * 高 32 bits ：bitmapIdx ，可以判断 Page 节点中的哪个 Subpage 的内存块，即 bitmap[bitmapIdx]
     * 低 32 bits ：memoryMapIdx ，可以判断所属 Chunk 的哪个 Page 节点，即 memoryMap[memoryMapIdx]
     */
    long allocate() {
        // 防御性编程，不存在这种情况
        if (elemSize == 0) {
            return toHandle(0);
        }

        //如果没有，或者已经被destroy
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获得下一个可用的 Subpage 在 bitmap 中的总体位置
        final int bitmapIdx = getNextAvail();
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置，高6位(12位的高6位，可不是32位的高6位)
        //因为bitmapIdx = bitMap中的位置 <<6 | 在 bitmap中数组元素 的第几 bits
        int q = bitmapIdx >>> 6;
        // 获得下一个可用的 Subpage 在 bitmap中数组元素 的第几 bits，低6位
        int r = bitmapIdx & 63;
        //判断bitmap[q]的值的第r位为0
        //这个时候我们要把bitmap[q]的第r为置为1，所以判断的也是这个第r位，所以我们向右偏移r位后第一位就是r位的值了
        //用来&1 就可以判断是不是0

        //这里的第r位实际上是第r+1位，因为findNextAvail0(int i, long bits)方法中r的取值范围是0~63
        //所以这里是移动r位 让第r位到第1位
        //比如7的二进制是0111，我们要分配第4位，获取第四位的值的方法就是右移动4位
        assert (bitmap[q] >>> r & 1) == 0;
        //这里是更新bitmap[q]的值
        // 1L << r得到第r位为1的值
        //与上原来的值也就是(r-1)到第1为都是1,因为是从右到左一位一位的过来的
        //得到bitmap[q]的值，也就是从第r位起全为1
        bitmap[q] |= 1L << r;
        //如果没有可用的subpage那么从队列中移除
        if (-- numAvail == 0) {
            removeFromPool();
        }
        //返回当前小块内存所在的位置，高位32位是bitmapIdx，低32位是memoryMapIdx
        return toHandle(bitmapIdx);
    }

    /**
     * 释放指定位置的 Subpage 内存块，并返回当前 Page 是否正在使用中( true )
     * @param bitmapIdx suboage在bitmap中的总位置
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 防御性编程，不存在这种情况。
        if (elemSize == 0) {
            return true;
        }
        //获得Subpage 在 bitmap 中数组的位置，高6位
        int q = bitmapIdx >>> 6;
        //获得Subpage 在 bitmap中数组元素 的第几 bits，低6位
        int r = bitmapIdx & 63;
        //和上面 allocate()的assert差不多，这里是判断bitmap[q]的值的第r位为1
        assert (bitmap[q] >>> r & 1) != 0;
        //这里是异或
        // 1L << r得到第r位为1的值
        //异或上原来的值，也就是第r为到第1位都是1,所以就将第r置为了0
        bitmap[q] ^= 1L << r;

        //这里将nextAvail的值指向当前的bitmapIdx，表示下一个可以用的subpage
        //这里就可以和getNextAvail方法中的 if (nextAvail >= 0) 直接返回nextAvail联系起来
        setNextAvail(bitmapIdx);

        //注意这里是numAvail++不是++numAvail
        //表示开始numAvail为0，该PoolSubpage已经被移除队列，
        //释放后numAvail+1，那么该PoolSubpage又可用了，重新调用addToPool(head)加入队列，并返回"可用"
        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            //当前Page 还在被使用（还有其他的subpage正在被使用）,返回可用
            return true;
        } else {
            //当前Page未被使用
            // Subpage not in use (numAvail == maxNumElems)
            //当前PoolSubpage为头节点，为双向链表中的唯一节点，返回可用
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }
            //移除，标记为已销毁
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            //从链表中移除
            removeFromPool();
            return false;
        }
    }

    /**
     * 将当前subpage 添加到head的下一个，并把head原来的下一个添加到当前的下一个
     * 就是把当前插入到head，和head.next的中间
     * @param head
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            //大于0，已经找好了位置，最新一个被释放的
            //等于0，初始的时候是0
            //这两种情况可以直接返回，并将nextAvail置为-1，表示下次需要去找位置分配
            this.nextAvail = -1;
            return nextAvail;
        }
        //没有找好，寻找下一个可分配的subpage位置
        return findNextAvail();
    }

    /**
     * 寻找下一个可分配的subpage位置
     * @return
     */
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;

        //便利实际使用了的bitmap数组
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            //因为初始化bitmap中的每个元素都是0，表示一个都没被使用
            //如果对bits取反等于0，表示bits所有的位都是1，那么该bits对应的64个subpage都被使用了
            if (~bits != 0) {
                //该bits中还有未被使用的
                return findNextAvail0(i, bits);
            }
        }
        //循环内没有返回的话，就是所有的subpage都被使用了
        return -1;
    }

    /**
     * @param i 下标
     * @param bits 值
     * @return
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        //i<<6=i*64;获取bitmap[i]，对应subpage的起始点
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            //计算当前bits是否被分配
            //等于0表示未被分配

            //不等于0，则表示被分配了，左移一位
            //因为分配，未分配是以未计算的，且是从低位（右边）开始的
            if ((bits & 1) == 0) {
                //获取分配的位置，因为baseVal一般是2的次幂（低位全是0），
                //拿64来说，64的低6位全是0，j的取值0~63
                // 与上 j 就相当于加上j，可以说是返回对应的下标
                int val = baseVal | j;
                //这里加上判断，保证不超过上限
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 去掉当前 bit
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        // 高位32位是bitmapIdx，低32位是memoryMapIdx
        // (long) bitmapIdx << 32：左移32位后高32位就是bitmapIdx原有的值，
        //低32位就是|memoryMapIdx的值
        //0x4000000000000000L用来防止bitmapIdx=0和page混淆

        //因为有了 0x4000000000000000L(最高两位为 01 ，其它位为 0 )，
        // 所以获取 bitmapIdx 时，通过 handle >>> 32 & 0x3FFFFFFF 操作。
        // 使用 0x3FFFFFFF( 最高两位为 00 ，其它位为 1 ) 进行消除 0x4000000000000000L 带来的影响。
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}

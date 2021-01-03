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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * Jemalloc 算法将每个 Arena 切分成多个小块 Chunk
 * Jemalloc 建议为 4MB ，Netty 默认使用为 16MB
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;
    /**
     * 所属 Arena 对象
     */
    final PoolArena<T> arena;
    /**
     * 内存空间。
     * 即用于 PooledByteBuf.memory 属性，有 Direct ByteBuffer 和 byte[] 字节数组
     * @see PooledByteBuf#memory
     */
    final T memory;
    /**
     * 是否非池化
     *
     * @see #PoolChunk(PoolArena, Object, int, int) 非池化。当申请的内存大小为 Huge 类型时，创建一整块 Chunk ，并且不拆分成若干 Page
     * @see #PoolChunk(PoolArena, Object, int, int, int, int, int) 池化
     */
    final boolean unpooled;


    final int offset;
    /**
     * 分配信息满二叉树
     * 长度为 {@link #maxSubpageAllocs} *2
     * index 为节点编号
     */
    private final byte[] memoryMap;
    /**
     * 高度信息满二叉树
     * 长度为 {@link #maxSubpageAllocs} *2
     * index 为节点编号
     */
    private final byte[] depthMap;
    /**
     * PoolSubpage 数组
     * 用来缓存PoolSubpage对象，当某个page被当作subpages分配后，被释放，内存回收只是修改该page内存可以重新分配，不释放PoolSubpage对象
     * 下次再在改page位置分配时，直接取出该PoolSubpage对象使用
     */
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    /**
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
     * 默认为-8192
     * 对于 -8192 的二进制，除了首 bits 为 1 ，其它都为 0 。这样，对于小于 8K 字节的申请，
     * 求 subpageOverflowMask & length 都等于 0 ；对于大于 8K 字节的申请，求 subpageOverflowMask & length 都不等于 0 。
     * 相当于说，做了 if ( length < pageSize ) 的计算优化。
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     */
    private final int subpageOverflowMask;
    /**
     * Page 大小，默认 8KB = 8192B
     * 16MB/2048=8KB
     */
    private final int pageSize;
    /**
     * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192 。
     *
     * 具体用途，见 {@link #allocateRun(int)} 方法，计算指定容量所在满二叉树的层级。
     */
    private final int pageShifts;
    /**
     * 满二叉树的高度。默认为 11 。
     */
    private final int maxOrder;
    /**
     * Chunk 内存块占用大小。默认为 16M = 16 * 1024  。
     */
    private final int chunkSize;

    /**
     * log2 {@link #chunkSize} 的结果。默认为 log2( 16M ) = 24 。
     */
    private final int log2ChunkSize;

    /**
     * 可分配 {@link #subpages} 的数量，即数组大小。默认为 1 << {@link #maxOrder} = 1 << 11 = 2048 。
     */
    private final int maxSubpageAllocs;
    /**
     * 标记节点不可用。默认为 maxOrder + 1 = 12 。
     *
     * Used to mark memory as unusable
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.

    //对创建的ByteBuffer进行缓存的一个队列
    private final Deque<ByteBuffer> cachedNioBuffers;

    /**
     * 剩余可用字节数
     */
    private int freeBytes;

    /**
     * 所属 PoolChunkList 对象
     */
    PoolChunkList<T> parent;

    /**
     * 上一个 Chunk 对象
     */
    PoolChunk<T> prev;

    /**
     * 下一个 Chunk 对象
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    /**
     * private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;
     * 池化
     * 默认情况下，对于 分配 16M 以内的内存空间时，Netty 会分配一个 Normal 类型的 Chunk 块。并且，该 Chunk 块在使用完后，进行池化缓存，重复使用。
     */
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        //是否非池化 初始化，小于16M 池化
        unpooled = false;
        //属于哪个 arena
        this.arena = arena;
        //实际的内存对象(ByteBuf 或 byte[])
        this.memory = memory;
        //初始化 pageSize大小，池化的话一般是8kb
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        //满二叉树的高度。默认为 11（实际是12层）
        this.maxOrder = maxOrder;
        //16M
        this.chunkSize = chunkSize;
        //对其量
        this.offset = offset;
        //表示该节点 已经被分配了
        unusable = (byte) (maxOrder + 1);
        //log2(16M)
        log2ChunkSize = log2(chunkSize);
        //减一再取反 得到-pageSize (用来进行位运算)
        //而我们平时的补码 加一再取反 得到一个负数，是用来做逻辑运算的(加，减)
        subpageOverflowMask = ~(pageSize - 1);
        //剩余可用字节数 初始为chunkSize
        freeBytes = chunkSize;
        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        //最大的Subpage数量，就是最后一层的数量
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        //这里为什么是maxSubpageAllocs << 1 而不是maxSubpageAllocs << 1 -1 因为是数组的memoryMap[0]是不使用的
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        //表示memoryMap[0]，depthMap[0]不使用
        int memoryMapIndex = 1;
        // 初始化 memoryMap 和 depthMap的值
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                //depthMap的值初始化后不再改变，memoryMap的值则随着节点分配而改变
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        //初始化subpages 数组 ->new PoolSubpage[maxSubpageAllocs]
        subpages = newSubpageArray(maxSubpageAllocs);

        //一个缓存的双端队列 todo
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled.
     * 非池化
     *默认情况下，对于分配 16M 以上的内存空间时，Netty 会分配一个 Huge 类型的特殊的 Chunk 块。
     * 并且，由于 Huge 类型的 Chunk 占用内存空间较大，比较特殊，所以该 Chunk 块在使用完后，立即释放，不进行重复使用。
     */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    /**
     * 判断chunk剩余比列
     * PoolChunk 对 PoolChunkMetric 接口的实现
     * @return
     */
    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        // 部分使用，最高 99%
        //(int) 表示取整数部分
        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }


    /**
     * 给bug 分配内存
     * @param buf 待分配内存的PooledByteBuf
     * @param reqCapacity 实际传入的大小
     * @param normCapacity 需要的大小 根据reqCapacity 计算后的2^n 的一个值
     * @return 返回是否分配成功
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        //表示分配的page的下标
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) {
            // >= pageSize 大于8kb 分配page内存块
            handle =  allocateRun(normCapacity);
        } else {
            //小于8kb分配PollSubPage(将一个PoolPage拆成多个PollSubPage)
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        // 获得当前节点的子节点的层级
        int logChild = depth(id) + 1;
        while (id > 1) {
            //父节点的值
            int parentId = id >>> 1;
            //当前节点的值
            byte val1 = value(id);
            //兄弟节点的值
            byte val2 = value(id ^ 1);
            // 获得当前节点的层级
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            //判断节点是否可用
            if (val1 == logChild && val2 == logChild) {
                // 两个子节点都可用，则直接设置父节点的层级
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth 满足分配要求的深度比如8k就在11层，1w就在10层
     * @return index in memoryMap 返回满足分配要求的节点在memoryMap的下标
     */
    private int allocateNode(int d) {
        //获取第一个点，这里也可以看出数组的0位置是不使用的
        int id = 1;
        //这里重新左移d位，得到需要分配内存大小 起始下标的负数
        //(id & initial) == 0 表示下标还没有到第n层
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        // 获得根节点的值。
        byte val = value(id);
        // 如果根节点的值，大于 d ，说明，第 d 层没有符合的节点，也就是说 [0, d-1] 层也没有符合的节点。即，当前 Chunk 没有符合的节点。
        if (val > d) { // unusable
            return -1;
        }
        // <1> val<d 子节点可满足需求
        // <2> id & initial 来保证，高度小于 d 会继续循环，因为不管如何满足要分配内存大小的page一定在d层，比如8K就一定在11层，16k就一定在10层
        // 每一层的val值是会变化的，但是id，也就是每一层的下标值是不会变的，只要下标小于d层的下标，就能进入,
        // 所以第<1>个的判断条件好像没什么用（自己去掉该判断条件，也能正常分配）；
        //下面注释也说明了<2> ， id & initial == 1 << d 保证所有的ids都在d层， id小于d层 那么(id & initial) == 0
        // id & initial == 1 << d for all ids at depth d, for < d it is 0
        while (val < d || (id & initial) == 0) {
            // 高度加1，进入子节点
            id <<= 1;
            val = value(id);// = memoryMap[id]
            if (val > d) {
                // 上一
                //
                // 层的值是两个子节点的小值，左节点不满足，那么就是右节点满足
                // 右节点,因为根节点是从1开始的，所以左节点都是2的次幂，^1久相当于左节点+1得到右节点
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);

        //value == d 断言 value的层数是等于d的，表示memoryMap[id]
        //(id & initial) == 1 << d 表示到了对应的那一层 ； 如果y大于等于xx，且xx y 都为2^n 那么 -xx & y 等于y
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // mark as unusable
        setValue(id, unusable);

        //这里递归(while实现)更新父节点值为两个子节点的最小值
        //这里假如所需要的大小在高层，那么它的子节点是不会再更新的，
        //这样也没有问题，因为在上面while寻找对应节点的时候无法通过当前节点找下面的子节点，因为当前节点已经unuseable
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        //获取分配normCapacity所在的深度
        //这个怎么理解呢？
        //pageShifts是8kb的 左移位数， n=log2(normCapacity) 是1左移n位得到normCapacity
        //j=(log2(normCapacity) - pageShifts) 那么 j就是normCapacity是8kb的倍数
        //所以8kb分配在最底层->maxOrder-0 16kb就是倒数第二次 ->maxOrder-2
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        //获取满足分配要求的节点在memoryMap的下标
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        //获取对应规格，在PoolSubpage<T>[] 的头节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况。
        synchronized (head) {
            //获得一个可以分配8kb的page的下标
            int id = allocateNode(d);
            if (id < 0) {
                //如果已经没有空间了 直接返回
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;
            //subpage被分配了一个，容量就减去整个pageSize的大小
            freeBytes -= pageSize;

            //获取当前subpage为第11层的第几个，因为this.subpages 保存了一个chunk的哪个page作为subpage来做内存分配
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                //如果该Page还没有被分配过
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                //已经被分配过了，但是被释放了
                subpage.init(head, normCapacity);
            }
            // 分配 PoolSubpage 内存块
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        //获取低32位
        int memoryMapIdx = memoryMapIdx(handle);
        //获取高32位
        int bitmapIdx = bitmapIdx(handle);

        //表示是subPage
        //因为toHandle(int bitmapIdx)方法 高位 | 0x4000000000000000L，它高2位是01，就算是page的第一个subPage的第一块，
        // bitmapIdx也不为0
        if (bitmapIdx != 0) { // free a subpage
            //从subpages数组中获取哪个Page是作为PoolSubpage分配的。也就是找到对应subPage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            //找到该suPage 所在链的头节点
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            // 加锁，分释放过程会修改双向链表的结构，会存在多线程的情况。
            synchronized (head) {
                //bitmapIdx & 0x3FFFFFFF，去掉toHandle(int bitmapIdx)方法 高位 | 0x4000000000000000L的影响
                //0x3FFFFFFF的高2位置 是00 其他是1
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    //进入到这里表示该page还可用，直接退出
                    return;
                }
            }
        }

        //进入到这里，表示该Page不是作为subPage分配内存，或者是subPage，但是已经全部释放
        //释放Page
        freeBytes += runLength(memoryMapIdx);

        //更新该page和他父节点的状态
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        //将nioBuffer对象加入到缓存
        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            //内存块为 Page
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            /**
             * this ：所属chunk对象
             * nioBuffer：从cachedNioBuffers获取的缓存ByteBuffer
             * handle：page所在位置(低32位) subpage在page中的位置(高32位)
             * runOffset(memoryMapIdx) + offset：该page的偏移量+offset(对齐量)
             * reqCapacity：要求的容量
             * runLength(memoryMapIdx)：实际分配的容量
             * arena.parent.threadCache()：？
             */
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            // 内存块为 SubPage
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        /**
         * 说下这个
         * runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset
         * runOffset(memoryMapIdx)：该page的偏移量
         * (bitmapIdx & 0x3FFFFFFF)：subpage是在page中的第几个， & 0x3FFFFFFF去掉高位的 01
         */
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    /**
     * 返回log2(val)
     * @param val
     * @return
     */
    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        //int的最大位数-1  减去 val 左边的0的个数(比如1就是31个 0就是32个)
        //n=log2(val) n+1就是val左边非0起的位数
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    /**
     * 计算下标位id的page的大小
     * @param id
     * @return
     */
    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        //这里的语法是 1<< (log2ChunkSize - depth(id))
        //假如是id是2048 那么depth(id)是11 ->24-11= 13
        //1<<13 就是8*1024
        //这里为什么是24-11呢？
        //因为chunKsize = 2^11(2048个page) * 8192(每个的page大小2^13) ->2^(11+13) =2^(24)
        //如果往上一层的话是 2^10(1024个page) * 16395(每个page大小2^14)->2^(10+14) =2^(24)
        //所以求Page的大小2^24 / 层数 ->2^(24-层数)  >  1<< (24-层数)
        return 1 << log2ChunkSize - depth(id);
    }

    /**
     * 计算下标位id的Page的起始偏移量
     * @param id
     * @return
     */
    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        //获取id在 depth(id) 这层的第几个
        int shift = id ^ 1 << depth(id);
        //计算偏移量
        return shift * runLength(id);
    }

    /**
     * 因为一个chunk被分为2048个page，
     * 当一个page被作为subpage时，那么一定是在11层，那么它的下标就会大于2048，
     * 同时2048 为2的11次方，那么大于等于2048，小于4096的数值异或上2048就相当于减去2018
     * 那么这样就可以得到当前subpage在11层的位置，从0开始
     * @param memoryMapIdx 可以分配的Page 在memoryMap数组中的下标
     * @return
     */
    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    /**
     * 销毁poolChunk
     * 堆内存就是让gc去释放
     * 堆外内存有cleaner就是调用去释放
     * 没cleaner就是用unsafe去释放
     */
    void destroy() {
        arena.destroyChunk(this);
    }

    public static void main(String[] args) {
        System.out.println(log2(16));
    }
}

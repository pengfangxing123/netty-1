/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.NEWLINE;
import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * 内存泄漏探测器
 * @param <T>
 */
public class ResourceLeakDetector<T> {

    private static final String PROP_LEVEL_OLD = "io.netty.leakDetectionLevel";
    private static final String PROP_LEVEL = "io.netty.leakDetection.level";
    /**
     * 默认内存检测级别
     */
    private static final Level DEFAULT_LEVEL = Level.SIMPLE;

    private static final String PROP_TARGET_RECORDS = "io.netty.leakDetection.targetRecords";
    private static final int DEFAULT_TARGET_RECORDS = 4;

    private static final String PROP_SAMPLING_INTERVAL = "io.netty.leakDetection.samplingInterval";
    // There is a minor performance benefit in TLR if this is a power of 2.
    private static final int DEFAULT_SAMPLING_INTERVAL = 128;

    /**
     * 每个 DefaultResourceLeak 记录的 Record 数量
     */
    private static final int TARGET_RECORDS;
    static final int SAMPLING_INTERVAL;

    /**
     * Represents the level of resource leak detection.
     */
    public enum Level {
        /**
         * Disables resource leak detection.
         */
        DISABLED,
        /**
         * Enables simplistic sampling resource leak detection which reports there is a leak or not,
         * at the cost of small overhead (default).
         */
        SIMPLE,
        /**
         * Enables advanced sampling resource leak detection which reports where the leaked object was accessed
         * recently at the cost of high overhead.
         */
        ADVANCED,
        /**
         * Enables paranoid resource leak detection which reports where the leaked object was accessed recently,
         * at the cost of the highest possible overhead (for testing purposes only).
         */
        PARANOID;

        /**
         * Returns level based on string value. Accepts also string that represents ordinal number of enum.
         *
         * @param levelStr - level string : DISABLED, SIMPLE, ADVANCED, PARANOID. Ignores case.
         * @return corresponding level or SIMPLE level in case of no match.
         */
        static Level parseLevel(String levelStr) {
            String trimmedLevelStr = levelStr.trim();
            for (Level l : values()) {
                if (trimmedLevelStr.equalsIgnoreCase(l.name()) || trimmedLevelStr.equals(String.valueOf(l.ordinal()))) {
                    return l;
                }
            }
            return DEFAULT_LEVEL;
        }
    }

    /**
     * 内存泄露检测等级
     */
    private static Level level;

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ResourceLeakDetector.class);

    static {
        // 获得是否禁用泄露检测
        final boolean disabled;
        if (SystemPropertyUtil.get("io.netty.noResourceLeakDetection") != null) {
            disabled = SystemPropertyUtil.getBoolean("io.netty.noResourceLeakDetection", false);
            logger.debug("-Dio.netty.noResourceLeakDetection: {}", disabled);
            logger.warn(
                    "-Dio.netty.noResourceLeakDetection is deprecated. Use '-D{}={}' instead.",
                    PROP_LEVEL, DEFAULT_LEVEL.name().toLowerCase());
        } else {
            disabled = false;
        }

        // 获得默认级别
        Level defaultLevel = disabled? Level.DISABLED : DEFAULT_LEVEL;

        //获得配置的级别字符串，从老版本的配置
        // First read old property name (兼容老版本）
        String levelStr = SystemPropertyUtil.get(PROP_LEVEL_OLD, defaultLevel.name());
        //获得配置的级别字符串，从新版本的配置
        // If new property name is present, use it
        levelStr = SystemPropertyUtil.get(PROP_LEVEL, levelStr);
        // 获得最终的级别
        Level level = Level.parseLevel(levelStr);

        // 初始化 TARGET_RECORDS
        TARGET_RECORDS = SystemPropertyUtil.getInt(PROP_TARGET_RECORDS, DEFAULT_TARGET_RECORDS);
        SAMPLING_INTERVAL = SystemPropertyUtil.getInt(PROP_SAMPLING_INTERVAL, DEFAULT_SAMPLING_INTERVAL);

        ResourceLeakDetector.level = level;
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_LEVEL, level.name().toLowerCase());
            logger.debug("-D{}: {}", PROP_TARGET_RECORDS, TARGET_RECORDS);
        }
    }

    /**
     * @deprecated Use {@link #setLevel(Level)} instead.
     */
    @Deprecated
    public static void setEnabled(boolean enabled) {
        setLevel(enabled? Level.SIMPLE : Level.DISABLED);
    }

    /**
     * Returns {@code true} if resource leak detection is enabled.
     */
    public static boolean isEnabled() {
        return getLevel().ordinal() > Level.DISABLED.ordinal();
    }

    /**
     * Sets the resource leak detection level.
     */
    public static void setLevel(Level level) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        ResourceLeakDetector.level = level;
    }

    /**
     * Returns the current resource leak detection level.
     */
    public static Level getLevel() {
        return level;
    }

    //DefaultResourceLeak 集合
    /** the collection of active resources */
    private final Set<DefaultResourceLeak<?>> allLeaks =
            Collections.newSetFromMap(new ConcurrentHashMap<DefaultResourceLeak<?>, Boolean>());
    /**
     * 引用队列
     */
    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<Object>();

    /**
     * 已汇报的内存泄露的资源类型的集合
     */
    private final ConcurrentMap<String, Boolean> reportedLeaks = PlatformDependent.newConcurrentHashMap();

    /**
     * 资源类型
     */
    private final String resourceType;
    /**
     * 采集频率
     */
    private final int samplingInterval;

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType) {
        this(simpleClassName(resourceType));
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType) {
        this(resourceType, DEFAULT_SAMPLING_INTERVAL, Long.MAX_VALUE);
    }

    /**
     * @deprecated Use {@link ResourceLeakDetector#ResourceLeakDetector(Class, int)}.
     * <p>
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     *
     * @param maxActive This is deprecated and will be ignored.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval, long maxActive) {
        this(resourceType, samplingInterval);
    }

    /**
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     */
    @SuppressWarnings("deprecation")
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval) {
        this(simpleClassName(resourceType), samplingInterval, Long.MAX_VALUE);
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     * <p>
     * @param maxActive This is deprecated and will be ignored.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType, int samplingInterval, long maxActive) {
        if (resourceType == null) {
            throw new NullPointerException("resourceType");
        }

        this.resourceType = resourceType;
        this.samplingInterval = samplingInterval;
    }

    /**
     * Creates a new {@link ResourceLeak} which is expected to be closed via {@link ResourceLeak#close()} when the
     * related resource is deallocated.
     *
     * @return the {@link ResourceLeak} or {@code null}
     * @deprecated use {@link #track(Object)}
     */
    @Deprecated
    public final ResourceLeak open(T obj) {
        return track0(obj);
    }

    /**
     * 给指定资源( 例如 ByteBuf 对象 )创建一个检测它是否泄漏的 ResourceLeakTracker 追踪器对象
     * 跟踪一个池化资源
     * 它需要在这个资源被释放的时候调用close方法
     * Creates a new {@link ResourceLeakTracker} which is expected to be closed via
     * {@link ResourceLeakTracker#close(Object)} when the related resource is deallocated.
     *
     * @return the {@link ResourceLeakTracker} or {@code null}
     */
    @SuppressWarnings("unchecked")
    public final ResourceLeakTracker<T> track(T obj) {
        return track0(obj);
    }

    /**
     * 追踪器创建逻辑
     * @param obj
     * @return
     */
    @SuppressWarnings("unchecked")
    private DefaultResourceLeak track0(T obj) {
        Level level = ResourceLeakDetector.level;

        // DISABLED 级别，不创建
        if (level == Level.DISABLED) {
            return null;
        }

        if (level.ordinal() < Level.PARANOID.ordinal()) {
            // 随机 低于PARANOID等级 是取1%来监测，概率为 1 / samplingInterval ，创建 DefaultResourceLeak 对象。
            // 默认情况下 samplingInterval = 128 ，约等于 1%
            if ((PlatformDependent.threadLocalRandom().nextInt(samplingInterval)) == 0) {
                // 汇报内存是否泄漏
                reportLeak();
                // 创建 DefaultResourceLeak 对象
                return new DefaultResourceLeak(obj, refQueue, allLeaks);
            }
            return null;
        }
        // 汇报内存是否泄漏
        reportLeak();
        return new DefaultResourceLeak(obj, refQueue, allLeaks);
    }

    private void clearRefQueue() {
        for (;;) {
            @SuppressWarnings("unchecked")
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            // 清理
            ref.dispose();
        }
    }

    private void reportLeak() {
        // 如果不允许打印错误日志，则无法汇报，清理队列，并直接结束。
        if (!logger.isErrorEnabled()) {
            clearRefQueue();
            return;
        }

        // 检查和报告之前所有的泄漏
        // Detect and report previous leaks.
        for (;;) {
            @SuppressWarnings("unchecked")
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }

            // 清理，并返回是否内存泄露
            if (!ref.dispose()) {
                continue;
            }

            // 到这里说明已经产生泄露了
            // 获取这个泄露的相关记录的字符串
            String records = ref.toString();
            // 利用putIfAbsent 相同 Record 日志，只汇报一次
            if (reportedLeaks.putIfAbsent(records, Boolean.TRUE) == null) {
                if (records.isEmpty()) {
                    reportUntracedLeak(resourceType);
                } else {
                    reportTracedLeak(resourceType, records);
                }
            }
        }
    }

    /**
     * This method is called when a traced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportTracedLeak(String resourceType, String records) {
        logger.error(
                "LEAK: {}.release() was not called before it's garbage-collected. " +
                "See http://netty.io/wiki/reference-counted-objects.html for more information.{}",
                resourceType, records);
    }

    /**
     * This method is called when an untraced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportUntracedLeak(String resourceType) {
        logger.error("LEAK: {}.release() was not called before it's garbage-collected. " +
                "Enable advanced leak reporting to find out where the leak occurred. " +
                "To enable advanced leak reporting, " +
                "specify the JVM option '-D{}={}' or call {}.setLevel() " +
                "See http://netty.io/wiki/reference-counted-objects.html for more information.",
                resourceType, PROP_LEVEL, Level.ADVANCED.name().toLowerCase(), simpleClassName(this));
    }

    /**
     * @deprecated This method will no longer be invoked by {@link ResourceLeakDetector}.
     */
    @Deprecated
    protected void reportInstancesLeak(String resourceType) {
    }

    /**
     * 内存泄露追踪器
     * @param <T>
     */
    @SuppressWarnings("deprecation")
    private static final class DefaultResourceLeak<T>
            extends WeakReference<Object> implements ResourceLeakTracker<T>, ResourceLeak {

        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicReferenceFieldUpdater<DefaultResourceLeak<?>, Record> headUpdater =
                (AtomicReferenceFieldUpdater)
                        AtomicReferenceFieldUpdater.newUpdater(DefaultResourceLeak.class, Record.class, "head");

        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicIntegerFieldUpdater<DefaultResourceLeak<?>> droppedRecordsUpdater =
                (AtomicIntegerFieldUpdater)
                        AtomicIntegerFieldUpdater.newUpdater(DefaultResourceLeak.class, "droppedRecords");

        @SuppressWarnings("unused")
        /**
         * Record 链的头节点
         *
         * 看完 {@link #record()} 方法后，实际上，head 是尾节点，即最后( 新 )的一条 Record 。
         */
        private volatile Record head;
        @SuppressWarnings("unused")
        /**
         * 丢弃的 Record 计数
         */
        private volatile int droppedRecords;

        /**
         * DefaultResourceLeak 集合。来自 {@link ResourceLeakDetector#allLeaks}
         * 通过判断allLeaks是否包含DefaultResourceLeak来判断 xxxLeakAwareByteBuf是否是手动回收的，
         * 如果手动回收调用{@link  DefaultResourceLeak#close()} 话会从allLeaks中移除DefaultResourceLeak
         * 如果是jvm回收了，我们没有close的话，DefaultResourceLeak是没有从allLeaks移除的
         *
         *
         * 有说为什么不直接给DefaultResourceLeak一个boolen值来表有没有正确回收呢
         * 因为如果xxxLeakAwareByteBuf被Jvm 回收DefaultResourceLeak也会被回收
         * 这里用set，资料说是对被DefaultResourceLeak的一个强引用，这样即使持有DefaultResourceLeak的对象xxxLeakAwareByteBuf被回收了
         * DefaultResourceLeak也不会被回收(这个说法好像是提交了Issue，得到了作者得回复)
         *
         * 实际上如果DefaultResourceLeak对象被回收了也会放到refQueue中，这个好像也是个强引用？
         * 所以这里还是有个疑问
         */
        private final Set<DefaultResourceLeak<?>> allLeaks;
        /**
         * hash 值
         *
         * 保证 {@link #close(Object)} 传入的对象，就是 {@link #referent} 对象
         */
        private final int trackedHash;

        DefaultResourceLeak(
                Object referent,
                ReferenceQueue<Object> refQueue,
                Set<DefaultResourceLeak<?>> allLeaks) {
            // 调用WeakReference的调用方法
            // 注意传入了ReferenceQueue， 完成GC的通知
            super(referent, refQueue);

            assert referent != null;

            // 这里生成了我们引用指向的资源的hashCode
            // 注意这里我们存储了hashCode而非资源对象本身
            // 因为如果存储资源对象本身的话我们就形成了强引用，导致资源不可能被GC
            // Store the hash of the tracked object to later assert it in the close(...) method.
            // It's important that we not store a reference to the referent as this would disallow it from
            // be collected via the WeakReference.
            trackedHash = System.identityHashCode(referent);
            // 将当前的DefaultResourceLeak示例加入到allLeaks集合里面
            // 这个集合是由它跟踪的资源所属的ResourceLeakDetector管理
            // 这个集合在后面判断资源是否正确释放扮演重要角色
            allLeaks.add(this);

            // 初始化设置当前DefaultResourceLeak所关联的record
            // Create a new Record so we always have the creation stacktrace included.
            headUpdater.set(this, new Record(Record.BOTTOM));
            this.allLeaks = allLeaks;
        }

        /**
         * 单纯记录一个调用点，没有任何额外提示信息
         */
        @Override
        public void record() {
            record0(null);
        }

        /**
         * 记录一个调用点，并附上额外信息
         */
        @Override
        public void record(Object hint) {
            record0(hint);
        }

        /**
         * /**
         *这个函数非常有意思
         * 有一个预设的TARGET_RECORDS字段
         * 这里有个问题，如果这个资源会在很多地方被记录，
         * 那么这个跟踪这个资源的DefaultResourceLeak的Record就会有很多
         * 但并不是每个记录都需要被记录，否则就会对内存和运行都会造成压力
         * 因为每个Record都会记录整个调用栈
         * 因此需要对记录做取舍
         * 这里有几个原则
         * 1. 所有record都会用一根单向链表来保存
         * 2. 最新的record永远都会被记录
         * 3. 小于TARGET_RECORDS数目的record也会被记录
         * 4. 当数目大于等于TARGET_RECORDS的时候，就会根据概率选择是用最新的record替换掉
         *    当前链表中头上的record(保证链表长度不会增加)，还是仅仅添加到头上的record之前
         *    (也就是增加链表长度)，当链表长度越大时，替换的概率也越大
         *          * @param hint
         *
         * This method works by exponentially backing off as more records are present in the stack. Each record has a
         * 1 / 2^n chance of dropping the top most record and replacing it with itself. This has a number of convenient
         * properties:
         *
         * <ol>
         * <li>  The current record is always recorded. This is due to the compare and swap dropping the top most
         *       record, rather than the to-be-pushed record.
         * <li>  The very last access will always be recorded. This comes as a property of 1.
         * <li>  It is possible to retain more records than the target, based upon the probability distribution.
         * <li>  It is easy to keep a precise record of the number of elements in the stack, since each element has to
         *     know how tall the stack is.
         * </ol>
         *
         * In this particular implementation, there are also some advantages. A thread local random is used to decide
         * if something should be recorded. This means that if there is a deterministic access pattern, it is now
         * possible to see what other accesses occur, rather than always dropping them. Second, after
         * {@link #TARGET_RECORDS} accesses, backoff occurs. This matches typical access patterns,
         * where there are either a high number of accesses (i.e. a cached buffer), or low (an ephemeral buffer), but
         * not many in between.
         *
         * The use of atomics avoids serializing a high number of accesses, when most of the records will be thrown
         * away. High contention only happens when there are very few existing records, which is only likely when the
         * object isn't shared! If this is a problem, the loop can be aborted and the record dropped, because another
         * thread won the race.
         */
        private void record0(Object hint) {
            // 如果TARGET_RECORDS小于等于0 表示不记录，默认为4
            // Check TARGET_RECORDS > 0 here to avoid similar check before remove from and add to lastRecords
            if (TARGET_RECORDS > 0) {
                Record oldHead;
                Record prevHead;
                Record newHead;
                boolean dropped;
                do {
                    // 如果链表头为null，说明已经close了
                    if ((prevHead = oldHead = headUpdater.get(this)) == null) {
                        // already closed.
                        return;
                    }
                    // 获取当前链表长度
                    //初始化时添加的pos是0->-1+1=0
                    final int numElements = oldHead.pos + 1;
                    if (numElements >= TARGET_RECORDS) {
                        // 获取是否替换的概率，先获取一个因子n
                        // 这个n最多为30，最小为链表长度 - TARGET_RECORDS
                        final int backOffFactor = Math.min(numElements - TARGET_RECORDS, 30);
                        // 这里有 1 / 2^n的概率来添加这个record而不丢弃原有的链表头record，也就是不替换
                        if (dropped = PlatformDependent.threadLocalRandom().nextInt(1 << backOffFactor) != 0) {
                            //这里就表示链表头record被丢弃了
                            prevHead = oldHead.next;
                        }
                    } else {
                        dropped = false;
                    }
                    // cas 更新record链表
                    newHead = hint != null ? new Record(prevHead, hint) : new Record(prevHead);
                } while (!headUpdater.compareAndSet(this, oldHead, newHead));
                // 增加丢弃的record数量
                if (dropped) {
                    droppedRecordsUpdater.incrementAndGet(this);
                }
            }
        }

        /**
         * 判断是否存在泄漏的关键
         * @return false 代表已经正确close
         *         true 代表并未正确close
         */
        boolean dispose() {
            // 清理对资源对象的引用
            clear();
            // 直接使用allLeaks.remove(this) 的结果来
            // 如果remove成功就说明之前close没有调用成功
            // 也就说明了这个监控对象并没有调用足够的release来完成资源释放
            // 如果remove失败说明之前已经完成了close的调用，一切正常
            return allLeaks.remove(this);
        }

        @Override
        public boolean close() {
            // 从allLeaks 集合中去除自己
            // allLeaks中是否包含自己就作为是否正确release和GC的标准
            if (allLeaks.remove(this)) {
                // Call clear so the reference is not even enqueued.
                // 如果成功去除自己，说明是正常流程
                // 清除掉对资源对象的引用
                clear();
                // 设置链表头record到null
                headUpdater.set(this, null);
                // 返回关闭成功
                return true;
            }
            // 说明自己已经被去除了，可能是重复close，或者是存在泄露，返回关闭失败
            return false;
        }

        @Override
        public boolean close(T trackedObject) {
            // 保证释放和跟踪的是同一个对象
            // Ensure that the object that was tracked is the same as the one that was passed to close(...).
            assert trackedHash == System.identityHashCode(trackedObject);

            try {
                // 调用真正close的逻辑
                return close();
            } finally {
                // 保证在这个方法调用之前跟踪的资源对象不会被GC
                // 具体原因可参见这个方法的注释，这里只需要注意
                // 如果在这个方法之前对象就被GC，就不能保证close是否正常
                // 因为如果GC之后再close，就有可能导致泄漏的误判
                // This method will do `synchronized(trackedObject)` and we should be sure this will not cause deadlock.
                // It should not, because somewhere up the callstack should be a (successful) `trackedObject.release`,
                // therefore it is unreasonable that anyone else, anywhere, is holding a lock on the trackedObject.
                // (Unreasonable but possible, unfortunately.)
                reachabilityFence0(trackedObject);
            }
        }

         /**
          *
          * 这个方法看上去很莫名，只在跟踪对象上调用了一个空synchronized块
          * 这里其实引申出来一个很奇葩的问题，就是JVM GC有可能会在一个对象的方法正在执行的时候
          * 就判定这个对象已经不可达，并把它给回收了，具体可以看这个帖子
          * https://stackoverflow.com/questions/26642153/finalize-called-on-strongly-reachable-object-in-java-8
          * 针对这个问题，Java 9 提供了Reference.reachabilityFence这个方法作为解决方案
          * 出于兼容性考虑，这里实现了一个netty版本的reachabilityFence方法，在ref上调用了空synchronized块
          * 来保证在这个方法调用前，JVM是不会对这个对象进行GC，(synchronized保证了不会出现指令重排)
          * 当然引入synchronized块就有可能会引入死锁，这个需要调用者来避免这个事情
          * 还有一个注意的就是这个方法一定要在finally块中使用，保证这个方法的调用会在整个流程的最后，从而保证GC不会执行
          *
         * Ensures that the object referenced by the given reference remains
         * <a href="package-summary.html#reachability"><em>strongly reachable</em></a>,
         * regardless of any prior actions of the program that might otherwise cause
         * the object to become unreachable; thus, the referenced object is not
         * reclaimable by garbage collection at least until after the invocation of
         * this method.
         *
         * <p> Recent versions of the JDK have a nasty habit of prematurely deciding objects are unreachable.
         * see: https://stackoverflow.com/questions/26642153/finalize-called-on-strongly-reachable-object-in-java-8
         * The Java 9 method Reference.reachabilityFence offers a solution to this problem.
         *
         * <p> This method is always implemented as a synchronization on {@code ref}, not as
         * {@code Reference.reachabilityFence} for consistency across platforms and to allow building on JDK 6-8.
         * <b>It is the caller's responsibility to ensure that this synchronization will not cause deadlock.</b>
         *
         * @param ref the reference. If {@code null}, this method has no effect.
         * @see java.lang.ref.Reference#reachabilityFence
         */
        private static void reachabilityFence0(Object ref) {
            if (ref != null) {
                synchronized (ref) {
                    // Empty synchronized is ok: https://stackoverflow.com/a/31933260/1151521
                }
            }
        }

        @Override
        public String toString() {
            //获取record链表
            Record oldHead = headUpdater.getAndSet(this, null);
            if (oldHead == null) {
                // Already closed
                //当是SimpleLeakAwareByteBuf的 leak时，不会记录record，但是会有一个初始的record，
                // 所以为空表示已经被tostring或者释放了
                return EMPTY_STRING;
            }

            //获取丢弃的record数量
            final int dropped = droppedRecordsUpdater.get(this);
            int duped = 0;

            //获取record链表长度
            int present = oldHead.pos + 1;
            // Guess about 2 kilobytes per stack trace
            StringBuilder buf = new StringBuilder(present * 2048).append(NEWLINE);
            buf.append("Recent access records: ").append(NEWLINE);

            int i = 1;
            Set<String> seen = new HashSet<String>(present);
            //Record.BOTTOM是一个标识 存在于record链尾部，相当于一个tail，在DefaultResourceLeak初始化时作为next存入
            for (; oldHead != Record.BOTTOM; oldHead = oldHead.next) {
                //获取每个Record代表的堆栈调用信息
                String s = oldHead.toString();
                if (seen.add(s)) {
                    if (oldHead.next == Record.BOTTOM) {
                        buf.append("Created at:").append(NEWLINE).append(s);
                    } else {
                        buf.append('#').append(i++).append(':').append(NEWLINE).append(s);
                    }
                } else {
                    duped++;
                }
            }

            // 拼接 duped次数
            if (duped > 0) {
                buf.append(": ")
                        .append(duped)
                        .append(" leak records were discarded because they were duplicates")
                        .append(NEWLINE);
            }

            // 拼接 dropped (丢弃) 次数
            if (dropped > 0) {
                buf.append(": ")
                   .append(dropped)
                   .append(" leak records were discarded because the leak record count is targeted to ")
                   .append(TARGET_RECORDS)
                   .append(". Use system property ")
                   .append(PROP_TARGET_RECORDS)
                   .append(" to increase the limit.")
                   .append(NEWLINE);
            }

            buf.setLength(buf.length() - NEWLINE.length());
            return buf.toString();
        }
    }

    private static final AtomicReference<String[]> excludedMethods =
            new AtomicReference<String[]>(EmptyArrays.EMPTY_STRINGS);

    public static void addExclusions(Class clz, String ... methodNames) {
        Set<String> nameSet = new HashSet<String>(Arrays.asList(methodNames));
        // Use loop rather than lookup. This avoids knowing the parameters, and doesn't have to handle
        // NoSuchMethodException.
        //校验这些方法是否属于clz
        for (Method method : clz.getDeclaredMethods()) {
            if (nameSet.remove(method.getName()) && nameSet.isEmpty()) {
                break;
            }
        }
        //有属于的 抛出异常
        if (!nameSet.isEmpty()) {
            throw new IllegalArgumentException("Can't find '" + nameSet + "' in " + clz.getName());
        }
        String[] oldMethods;
        String[] newMethods;
        do {
            //一个staitc 的数组，i ->clz name i+1 ->method name
            oldMethods = excludedMethods.get();
            newMethods = Arrays.copyOf(oldMethods, oldMethods.length + 2 * methodNames.length);
            for (int i = 0; i < methodNames.length; i++) {
                newMethods[oldMethods.length + i * 2] = clz.getName();
                newMethods[oldMethods.length + i * 2 + 1] = methodNames[i];
            }
        } while (!excludedMethods.compareAndSet(oldMethods, newMethods));
    }

    private static final class Record extends Throwable {
        private static final long serialVersionUID = 6065153674892850720L;
        /**
         * 尾节点的单例
         */
        private static final Record BOTTOM = new Record();
        /**
         * hint 字符串
         */
        private final String hintString;
        /**
         * 下一个节点
         */
        private final Record next;
        /**
         * 位置
         */
        private final int pos;

        Record(Record next, Object hint) {
            // This needs to be generated even if toString() is never called as it may change later on.
            hintString = hint instanceof ResourceLeakHint ? ((ResourceLeakHint) hint).toHintString() : hint.toString();
            this.next = next;
            this.pos = next.pos + 1;
        }

        Record(Record next) {
           hintString = null;
           this.next = next;
           this.pos = next.pos + 1;
        }

        // Used to terminate the stack
        private Record() {
            hintString = null;
            next = null;
            pos = -1;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(2048);
            if (hintString != null) {
                buf.append("\tHint: ").append(hintString).append(NEWLINE);
            }

            // Append the stack trace.
            //获取调用的堆栈信息，因为 Record extends Throwable
            // 依托于Throwable的getStackTrace方法，获取创建时候的调用栈
            StackTraceElement[] array = getStackTrace();
            // Skip the first three elements.
            // 跳过最开始的三个栈元素，因为它们就是调用record方法的那些栈信息，没必要显示了
            out: for (int i = 3; i < array.length; i++) {
                StackTraceElement element = array[i];
                // Strip the noisy stack trace elements.
                //是否要跳过
                //excludedMethods的规则 看addExclusions方法
                String[] exclusions = excludedMethods.get();
                for (int k = 0; k < exclusions.length; k += 2) {
                    if (exclusions[k].equals(element.getClassName())
                            && exclusions[k + 1].equals(element.getMethodName())) {
                        continue out;
                    }
                }

                buf.append('\t');
                buf.append(element.toString());
                buf.append(NEWLINE);
            }
            return buf.toString();
        }
    }
}

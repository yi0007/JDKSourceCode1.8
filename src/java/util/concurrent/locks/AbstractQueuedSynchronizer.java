/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */

/**
 * https://www.jianshu.com/p/e7659436538b
 * 1. 内部含有两条 Queue(Sync Queue, Condition Queue), 这两条 Queue 后面会详细说明.
 * 2. AQS 内部定义获取锁(acquire), 释放锁(release)的主逻辑, 子类实现响应的模版方法即可
 * 3. 支持共享和独占两种模式(共享模式时只用 Sync Queue, 独占模式有时只用 Sync Queue, 但若涉及 Condition, 则还有 Condition Queue); 独占是排他的.
 * 4. 支持 不响应中断获取独占锁(acquire), 响应中断获取独占锁(acquireInterruptibly), 超时获取独占锁(tryAcquireNanos); 不响应中断获取共享锁(acquireShared), 响应中断获取共享锁(acquireSharedInterruptibly), 超时获取共享锁(tryAcquireSharedNanos);
 * 5. 在子类的 tryAcquire, tryAcquireShared 中实现公平与非公平的区分
 *
 * 整个 AQS 非为以下几部:
 * 1.Node 节点, 用于存放获取线程的节点, 存在于 Sync Queue, Condition Queue, 这些节点主要的区分在于 waitStatus 的值(下面会详细叙述)
 * 2.Condition Queue, 这个队列是用于独占模式中, 只有用到 Condition.awaitXX 时才会将 node加到 tail 上(PS: 在使用 Condition的前提是已经获取 Lock)
 * 3.Sync Queue, 独占、共享的模式中均会使用到的存放 Node 的 CLH queue(主要特点是, 队列中总有一个 dummy 节点, 后继节点获取锁的条件由前继节点决定, 前继节点在释放 lock 时会唤醒sleep中的后继节点)
 * 4.ConditionObject, 用于独占的模式, 主要是线程释放lock, 加入 Condition Queue, 并进行相应的 signal 操作, 详情点击这里 Java 8 源码分析 Condition（https://www.jianshu.com/p/52089c4eefdd）
 * 独占的获取lock (acquire, release), 例如 ReentrantLock 就是使用这种, 详情点击这里 Java 8 源码分析 ReentrantLock（https://www.jianshu.com/p/3f3417dbcac4）
 * 共享的获取lock (acquireShared, releaseShared), 例如 ReeantrantReadWriteLock（https://www.jianshu.com/p/6923c126e762）, Semaphore（https://www.jianshu.com/p/bb40a8073111）, CountDownLatch（https://www.jianshu.com/p/a042cf7a261c）
 */
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     *
     * Node 节点是代表获取lock的线程, 存在于 Condition Queue, Sync Queue 里面， 而其主要的分别就是 nextWaiter (标记共享还是独占)
     * waitStatus 标记node的状态(PS: 这是关键, 理解了 waitStatus 的变化流程, 就能理解整个 AQS)
     */
    static final class Node {
        /** Marker to indicate a node is waiting in shared mode */
        /** 标识节点是否是 共享的节点(这样的节点只存在于 Sync Queue 里面) */
        static final Node SHARED = new Node();
        /** Marker to indicate a node is waiting in exclusive mode */
        /** 标识节点是 独占模式 */
        static final Node EXCLUSIVE = null;

        /** waitStatus value to indicate thread has cancelled */
        /**
         *  CANCELLED 说明节点已经 取消获取 lock 了(一般是由于 interrupt 或 timeout 导致的)
         *  很多时候是在 cancelAcquire 里面进行设置这个标识
         */
        static final int CANCELLED =  1;
        /** waitStatus value to indicate successor's thread needs unparking */
        /**
         * 标识后继的节点处于阻塞状态，当前节点在释放同步状态或被取消时，需要通知后继节点继续运行。每个节点在阻塞前，需要标记其前驱节点的状态为SIGNAL。
         * SIGNAL 标识当前节点的后继节点需要唤醒(PS: 这个通常是在 独占模式下使用, 在共享模式下有时用 PROPAGATE)
         */
        static final int SIGNAL    = -1;
        /** waitStatus value to indicate thread is waiting on condition */
        // 标识当前节点是作为等待队列节点使用的。当前节点在 Condition Queue 里面
        static final int CONDITION = -2;
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        /**
         * 当前节点获取到 lock 或进行 release lock 时, 共享模式的最终状态是 PROPAGATE(PS: 有可能共享模式的节点变成 PROPAGATE 之前就被其后继节点抢占 head 节点, 而从Sync Queue中被踢出掉)
         */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block.
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.)
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *   0:          None of the above
         *
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         */
        volatile int waitStatus;

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */
        /**
         * 前驱节点
         *
         * 节点在 Sync Queue 里面时的前继节点(主要来进行 skip CANCELLED 的节点)
         * 注意: 根据 addWaiter方法:
         *  1. prev节点在队列里面, 则 prev != null 肯定成立
         *  2. prev != null 成立, 不一定 node 就在 Sync Queue 里面
         */
        volatile Node prev;

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */
        /**
         * 后继节点
         *
         * Node 在 Sync Queue 里面的后继节点, 主要是在release lock 时进行后继节点的唤醒
         * 而后继节点在前继节点上打上 SIGNAL 标识, 来提醒他 release lock 时需要唤醒
         */
        volatile Node next;

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */
        // 当前节点代表的线程 获取 lock 的引用
        volatile Thread thread;

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         */
        // Node既可以作为同步队列节点使用，也可以作为Condition的等待队列节点使用
        // 在作为同步队列节点时，nextWaiter可能有两个值：EXCLUSIVE、SHARED标识当前节点是独占模式还是共享模式
        // 在作为等待队列节点使用时，nextWaiter保存后继节点
        /**
         * 作用分成两种:
         *  1. 在 Sync Queue 里面, nextWaiter用来判断节点是 共享模式, 还是独占模式
         *  2. 在 Condition queue 里面, 节点主要是链接且后继节点 (Condition queue是一个单向的, 不支持并发的 list)
         */
        Node nextWaiter;

        /**
         * Returns true if node is waiting in shared mode.
         */
        /** 当前节点是否是共享模式 */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        /**
         * 获取 node 的前继节点
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        /**
         * 初始化 Node 用于 Sync Queue 里面
         */
        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        /**
         * 初始化 Node 用于 Condition Queue 里面
         */
        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     */

    /**
     * Sync Queue 是一个类似于 CLH Queue 的并发安全, 双向, 用于独占和共享两种模式下的 queue.
     * 而当 Node 存在于 Sync Queue 时, waitStatus,， prev, next, thread, nextWaiter 均可能有值;
     * waitStatus 可能是 SIGNAL, 0, PROPAGATE, CANCELLED;
     * 当节点不是 head 时一定prev != null(而 node.prev != null 不能说明节点一定存在于 Sync Queue);
     * node.next != null 则 node一定存在于Sync Queue, 而 node存在于 Sync Queue 则 node.next 就不一定 != null;
     * thread 则代表获取 lock 的线程;
     * nextWaiter 用于标示共享还是独占的获取 lock
     */

    /**
     *  头结点
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     */
    /**
     *  尾结点
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    /**
     *
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * 这个插入会检测head tail 的初始化, 必要的话会初始化一个 dummy 节点, 这个和 ConcurrentLinkedQueue 一样的
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor 返回的是前继节点
     */
    /**
     * 将节点 node 加入队列
     * 这里有个注意点
     * 情况:
     *      1. 首先 queue是空的
     *      2. 初始化一个 dummy 节点
     *      3. 这时再在tail后面添加节点(这一步可能失败, 可能发生竞争被其他的线程抢占)
     *  这里为什么要加入一个 dummy 节点呢?
     *      这里的 Sync Queue 是CLH lock的一个变种, 线程节点 node 能否获取lock的判断通过其前继节点
     *      而且这里在当前节点想获取lock时通常给前继节点 打上 signal 的标识(表示前继节点释放lock需要通知我来获取lock)
     *      若这里不清楚的同学, 请先看看 CLH lock的资料 (这是理解 AQS 的基础)
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            if (t == null) { // Must initialize // 1. 队列为空 初始化一个 dummy 节点 其实和 ConcurrentLinkedQueue 一样
                if (compareAndSetHead(new Node()))// 2. 初始化 head 与 tail (这个CAS成功后, head 就有值了, 详情将 Unsafe 操作)
                    tail = head;
            } else {
                node.prev = t;// 3. 先设置 Node.pre = pred (PS: 则当一个 node在Sync Queue里面时  node.prev 一定 != null, 但是 node.prev != null 不能说明其在 Sync Queue 里面, 因为现在的CAS可能失败 )
                if (compareAndSetTail(t, node)) {// 4. CAS node 到 tail
                    t.next = node;// 5. CAS 成功, 将 pred.next = node (PS: 说明 node.next != null -> 则 node 一定在 Sync Queue, 但若 node 在Sync Queue 里面不一定 node.next != null)
                    return t;
                }
            }
        }
    }

    /**
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    /**
     * 将当前的线程封装成 Node 加入到 Sync Queue 里面
     * 这里有个地方需要注意：就是初始化head, tail 的节点, 不一定是 head.next, 因为期间可能被其他的线程进行抢占了
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);// 1. 封装 Node
        // Try the fast path of enq; backup to full enq on failure
        Node pred = tail;
        if (pred != null) {// 2. pred != null -> 队列中已经有节点, 直接 CAS 到尾节点
            node.prev = pred;// 3. 先设置 Node.pre = pred (PS: 则当一个 node在Sync Queue里面时  node.prev 一定 != null(除 dummy node), 但是 node.prev != null 不能说明其在 Sync Queue 里面, 因为现在的CAS可能失败 )
            if (compareAndSetTail(pred, node)) {// 4. CAS node 到 tail
                pred.next = node;// 5. CAS 成功, 将 pred.next = node (PS: 说明 node.next != null -> 则 node 一定在 Sync Queue, 但若 node 在Sync Queue 里面不一定 node.next != null)
                return node;
            }
        }
        enq(node); // 6. 队列为空, 调用 enq 入队列
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * @param node the node
     */
    /**
     * 设置 head 节点(在独占模式没有并发的可能, 当共享的模式有可能)
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;// 清除线程引用
        node.prev = null;// 清除原来 head 的引用 <- 都是 help GC
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */
    /**
     * 唤醒 node 的后继节点
     *  * 这里有个注意点: 唤醒时会将当前node的标识归位为 0
     *  * 等于当前节点标识位 的流转过程: 0(刚加入queue) -> signal (被后继节点要求在释放时需要唤醒) -> 0 (进行唤醒后继节点)
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus;
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);// 1. 清除前继节点的标识

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         */
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {// 2. 这里若在 Sync Queue 里面存在想要获取 lock 的节点,则一定需要唤醒一下(跳过取消的节点)　（PS: s == null发生在共享模式的竞争释放资源）
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0) // 3. 找到 queue 里面最前面想要获取 Lock 的节点
                    s = t;
        }
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    /**
     * 特点: 当 Sync Queue中存在连续多个获取 共享lock的节点时, 会出现并发的唤醒后继节点(因为共享模式下获取lock后会唤醒近邻的后继节点来获取lock)
     *
     * 流程:
     * 1. 调用子类的 tryReleaseShared来进行释放 lock
     * 2. 判断是否需要唤醒后继节点来获取 lock
     *
     * 调用流分类：
     *  场景1: Sync Queue 里面存在 : 1(共享) -> 2(共享) -> 3(共享) -> 4(共享)
     *    节点1获取 lock 后调用 setHeadAndPropagate -> doReleaseShared 唤醒 节点2 —> 接下来 node 1 在 release 时再次 doReleaseShared, 而 node 2在获取 lock 后调用 setHeadAndPropagate 时再次 doReleaseShared -> 直至到 node 4, node 4的状态变成 PROPAGATE (期间可能有些节点还没设置为 PROPAGATE 就被其他节点调用 setHead 而踢出 Sync Queue)
     *
     *  场景2: Sync Queue 里面存在 : 1(共享) -> 2(共享) -> 3(独占) -> 4(共享)
     *    节点1获取 lock 后调用 setHeadAndPropagate -> doReleaseShared 唤醒 节点2 —> 接下来 node 1 在 release 时再次 doReleaseShared, 而 node 2 在获取 lock 后
     *    这是发现后继节点不是共享的, 则 Node 2 不在 setHeadAndPropagate 中调用 doReleaseShared, 而Node 3 没有获取lock, 将 Node 2 变成 SIGNAL, 而 node 2 在 release lock 时唤醒 node 3, 而 node 3 最终在 release lock 时 释放 node 4， node 4在release lock后状态还是保持 0
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (;;) {
            Node h = head;// 1. 获取 head 节点, 准备 release
            if (h != null && h != tail) {// 2. Sync Queue 里面不为 空
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {// 3. h节点后面可能是 独占的节点, 也可能是 共享的, 并且请求了唤醒(就是给前继节点打标记 SIGNAL)
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))// 4. h 恢复  waitStatus 值置0 (为啥这里要用 CAS 呢, 因为这里的调用可能是在 节点刚刚获取 lock, 而其他线程又对其进行中断, 所用cas就出现失败)
                        continue;            // loop to recheck cases
                    unparkSuccessor(h);// 5. 唤醒后继节点
                }
                else if (ws == 0 &&
                         !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))//6. h后面没有节点需要唤醒, 则标识为 PROPAGATE 表示需要继续传递唤醒(主要是区别 独占节点最终状态0 (独占的节点在没有后继节点, 并且release lock 时最终 waitStatus 保存为 0))
                    continue;                // loop on failed CAS // 7. 同样这里可能存在竞争
            }
            if (h == head)                   // loop if head changed // 8. head 节点没变化, 直接 return(从这里也看出, 一个共享模式的 节点在其唤醒后继节点时, 只唤醒一个, 但是 它会在 获取 lock 时唤醒, 释放 lock 时也进行, 所以或导致竞争的操作)
                break;// head 变化了, 说明其他节点获取 lock 了, 自己的任务完成, 直接退出
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    /**
     * 清除因中断/超时而放弃获取lock的线程节点(此时节点在 Sync Queue 里面)
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        node.thread = null; // 1. 线程引用清空

        // Skip cancelled predecessors
        Node pred = node.prev;
        while (pred.waitStatus > 0)// 2.  若前继节点是 CANCELLED 的, 则也一并清除
            node.prev = pred = pred.prev;

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        Node predNext = pred.next;// 3. 这里的 predNext也是需要清除的(只不过在清除时的 CAS 操作需要 它)

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        node.waitStatus = Node.CANCELLED;// 4. 标识节点需要清除

        // If we are the tail, remove ourselves.
        if (node == tail && compareAndSetTail(node, pred)) {// 5. 若需要清除额节点是尾节点, 则直接 CAS pred为尾节点
            compareAndSetNext(pred, predNext, null);// 6. 删除节点predNext
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            if (pred != head &&
                ((ws = pred.waitStatus) == Node.SIGNAL || // 7. 后继节点需要唤醒(但这里的后继节点predNext已经 CANCELLED 了)
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&// 8. 将 pred 标识为 SIGNAL
                pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0) // 9. next.waitStatus <= 0 表示 next 是个一个想要获取lock的节点
                    compareAndSetNext(pred, predNext, next);
            } else {
                unparkSuccessor(node);// 若 pred 是头节点, 则此刻可能有节点刚刚进入 queue ,所以进行一下唤醒
            }

            node.next = node; // help GC
        }
    }

    /**
     * 判断是否阻塞线程方法
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    /**
     * shouldParkAfterFailedAcquire 这个方法最终的作用:
     *  本节点在进行 sleep 前一定需要给 前继节点打上 SIGNAL 标识(
     *  因为前继节点在 release lock 时会根据 这个标识决定是否需要唤醒后继节点来获取 lock,
     *  若释放时 标识是0, 则说明 Sync queue 里面没有等待获取lock的线程, 或Sync queue里面的节点正在获取 lock)
     *
     *  一般流程:
     *      1. 第一次进入此方法 前继节点状态是 0, 则 CAS 为SIGNAL 返回 false(干嘛返回的是FALSE <- 主要是为了再次 tryAcquire 一下, 说不定就能获取锁呢)
     *      2. 第二次进来 前继节点标志为SIGNAL, ok, 标识好了, 这下就可以安心睡觉, 不怕前继节点在释放lock后不进行唤醒我了
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        if (ws == Node.SIGNAL)// 1. 判断是否已经给前继节点打上标识SIGNAL, 为前继节点释放 lock 时唤醒自己做准备
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;
        if (ws > 0) { // 2. 遇到个 CANCELLED 的节点 (ws > 0 只可能是 CANCELLED 的节点, 也就是 获取中被中断, 或超时的节点)
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                node.prev = pred = pred.prev;// 3. 跳过所有 CANCELLED 的节点
            } while (pred.waitStatus > 0);// 跳过 CANCELLED 节点
            pred.next = node;
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);// 4. 到这里 ws 只可能是 0 或 PROPAGATE (用于 共享模式的, 所以在共享模式中的提醒前继节点唤醒自己的方式,也是给前继节点打上 SIGNAL标识 见 方法 "doReleaseShared" -> "!compareAndSetWaitStatus(h, Node.SIGNAL, 0)" -> unparkSuccessor)
        }
        return false;
    }

    /**
     * 自我中断, 这主要是怕外面的线程不知道整个获取的过程中是否中断过, 所以才 ....
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * 中断当前线程, 并且返回此次的唤醒是否是通过中断
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();//  Thread.interrupted() 会清除中断标识, 并返上次的中断标识
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    /**
     * 不支持中断的获取锁
     * 主逻辑:
     *  1. 当当前节点的前继节点是head节点时先 tryAcquire获取一下锁, 成功的话设置新 head, 返回
     *  2. 第一步不成功, 检测是否需要sleep, 需要的话就 sleep, 等待前继节点在释放lock时唤醒 或通过中断来唤醒
     *  3. 整个过程可能需要blocking nonblocking 几次
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();// 1. 获取当前节点的前继节点 (当一个node在 Sync Queue 里面, 并且没有获取 lock 的 node 的前继节点不可能是 null)
                if (p == head && tryAcquire(arg)) {// 2. 判断前继节点是否是head节点(前继节点是head, 存在两种情况 (1) 前继节点现在占用 lock (2)前继节点是个空节点, 已经释放 lock, node 现在有机会获取 lock); 则再次调用 tryAcquire尝试获取一下
                    setHead(node);// 3. 获取 lock 成功, 直接设置 新head(原来的head可能就直接被回收)
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;// 4. 返回在整个获取的过程中是否被中断过 ; 但这又有什么用呢? 若整个过程中被中断过, 则最后我在 自我中断一下 (selfInterrupt), 因为外面的函数可能需要知道整个过程是否被中断过
                }
                if (shouldParkAfterFailedAcquire(p, node) &&// 5. 调用 shouldParkAfterFailedAcquire 判断是否需要中断(这里可能会一开始 返回 false, 但在此进去后直接返回 true(主要和前继节点的状态是否是 signal))
                    parkAndCheckInterrupt())// 6. 现在lock还是被其他线程占用 那就睡一会, 返回值判断是否这次线程的唤醒是被中断唤醒
                    interrupted = true;
            }
        } finally {
            if (failed)// 7. 在整个获取中出错
                cancelAcquire(node);// 8. 清除 node 节点(清除的过程是先给 node 打上 CANCELLED标志, 然后再删除)
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);// 1. 将当前的线程封装成 Node 加入到 Sync Queue 里面
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();// 2. 获取当前节点的前继节点 (当一个n在 Sync Queue 里面, 并且没有获取 lock 的 node 的前继节点不可能是 null)
                if (p == head && tryAcquire(arg)) {// 3. 判断前继节点是否是head节点(前继节点是head, 存在两种情况 (1) 前继节点现在占用 lock (2)前继节点是个空节点, 已经释放 lock, node 现在有机会获取 lock); 则再次调用 tryAcquire尝试获取一下
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&// 4. 调用 shouldParkAfterFailedAcquire 判断是否需要中断(这里可能会一开始 返回 false, 但在此进去后直接返回 true(主要和前继节点的状态是否是 signal))
                    parkAndCheckInterrupt())// 5. 现在lock还是被其他线程占用 那就睡一会, 返回值判断是否这次线程的唤醒是被中断唤醒
                    throw new InterruptedException();// 6. 线程此时唤醒是通过线程中断, 则直接抛异常
            }
        } finally {
            if (failed)// 7. 在整个获取中出错(比如线程中断)
                cancelAcquire(node);// 8. 清除 node 节点(清除的过程是先给 node 打上 CANCELLED标志, 然后再删除)
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;// 0. 计算截至时间
        final Node node = addWaiter(Node.EXCLUSIVE);// 1. 将当前的线程封装成 Node 加入到 Sync Queue 里面
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();// 2. 获取当前节点的前继节点 (当一个n在 Sync Queue 里面, 并且没有获取 lock 的 node 的前继节点不可能是 null)
                if (p == head && tryAcquire(arg)) {// 3. 判断前继节点是否是head节点(前继节点是head, 存在两种情况 (1) 前继节点现在占用 lock (2)前继节点是个空节点, 已经释放 lock, node 现在有机会获取 lock); 则再次调用 tryAcquire尝试获取一下
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();// 4. 计算还剩余的时间
                if (nanosTimeout <= 0L)// 5. 时间超时, 直接返回
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&// 6. 调用 shouldParkAfterFailedAcquire 判断是否需要中断(这里可能会一开始 返回 false, 但在此进去后直接返回 true(主要和前继节点的状态是否是 signal))
                    nanosTimeout > spinForTimeoutThreshold)// 7. 若没超时, 并且大于spinForTimeoutThreshold, 则线程 sleep(小于spinForTimeoutThreshold, 则直接自旋, 因为效率更高 调用 LockSupport 是需要开销的)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())// 8. 线程此时唤醒是通过线程中断, 则直接抛异常
                    throw new InterruptedException();
            }
        } finally {
            if (failed)// 9. 在整个获取中出错(比如线程中断/超时)
                cancelAcquire(node);// 10. 清除 node 节点(清除的过程是先给 node 打上 CANCELLED标志, 然后再删除)
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);// 1. 将当前的线程封装成 Node 加入到 Sync Queue 里面
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();// 2. 获取当前节点的前继节点 (当一个n在 Sync Queue 里面, 并且没有获取 lock 的 node 的前继节点不可能是 null)
                if (p == head) {
                    int r = tryAcquireShared(arg);// 3. 判断前继节点是否是head节点(前继节点是head, 存在两种情况 (1) 前继节点现在占用 lock (2)前继节点是个空节点, 已经释放 lock, node 现在有机会获取 lock); 则再次调用 tryAcquireShared 尝试获取一下
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);// 4. 获取 lock 成功, 设置新的 head, 并唤醒后继获取  readLock 的节点
                        p.next = null; // help GC
                        if (interrupted)// 5. 在获取 lock 时, 被中断过, 则自己再自我中断一下(外面的函数可能需要这个参数)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&// 6. 调用 shouldParkAfterFailedAcquire 判断是否需要中断(这里可能会一开始 返回 false, 但在此进去后直接返回 true(主要和前继节点的状态是否是 signal))
                    parkAndCheckInterrupt())// 7. 现在lock还是被其他线程占用 那就睡一会, 返回值判断是否这次线程的唤醒是被中断唤醒
                    interrupted = true;
            }
        } finally {
            if (failed)// 8. 在整个获取中出错(比如线程中断/超时)
                cancelAcquire(node);// 9. 清除 node 节点(清除的过程是先给 node 打上 CANCELLED标志, 然后再删除)
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);// 1. 将当前的线程封装成 Node 加入到 Sync Queue 里面
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();// 2. 获取当前节点的前继节点 (当一个n在 Sync Queue 里面, 并且没有获取 lock 的 node 的前继节点不可能是 null)
                if (p == head) {
                    int r = tryAcquireShared(arg);// 3. 判断前继节点是否是head节点(前继节点是head, 存在两种情况 (1) 前继节点现在占用 lock (2)前继节点是个空节点, 已经释放 lock, node 现在有机会获取 lock); 则再次调用 tryAcquireShared 尝试获取一下
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);// 4. 获取 lock 成功, 设置新的 head, 并唤醒后继获取  readLock 的节点
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&// 5. 调用 shouldParkAfterFailedAcquire 判断是否需要中断(这里可能会一开始 返回 false, 但在此进去后直接返回 true(主要和前继节点的状态是否是 signal))
                    parkAndCheckInterrupt())// 6. 现在lock还是被其他线程占用 那就睡一会, 返回值判断是否这次线程的唤醒是被中断唤醒
                    throw new InterruptedException();// 7. 若此次唤醒是 通过线程中断, 则直接抛出异常
            }
        } finally {
            if (failed)// 8. 在整个获取中出错(比如线程中断/超时)
                cancelAcquire(node);// 9. 清除 node 节点(清除的过程是先给 node 打上 CANCELLED标志, 然后再删除)
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;// 0. 计算超时的时间
        final Node node = addWaiter(Node.SHARED);// 1. 将当前的线程封装成 Node 加入到 Sync Queue 里面
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();// 2. 获取当前节点的前继节点 (当一个n在 Sync Queue 里面, 并且没有获取 lock 的 node 的前继节点不可能是 null)
                if (p == head) {
                    int r = tryAcquireShared(arg);// 3. 判断前继节点是否是head节点(前继节点是head, 存在两种情况 (1) 前继节点现在占用 lock (2)前继节点是个空节点, 已经释放 lock, node 现在有机会获取 lock); 则再次调用 tryAcquireShared 尝试获取一下
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);// 4. 获取 lock 成功, 设置新的 head, 并唤醒后继获取  readLock 的节点
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();// 5. 计算还剩余的 timeout , 若小于0 则直接return
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&// 6. 调用 shouldParkAfterFailedAcquire 判断是否需要中断(这里可能会一开始 返回 false, 但在此进去后直接返回 true(主要和前继节点的状态是否是 signal))
                    nanosTimeout > spinForTimeoutThreshold)// 7. 在timeout 小于  spinForTimeoutThreshold 时 spin 的效率, 比 LockSupport 更高
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted()) // 8. 若此次唤醒是 通过线程中断, 则直接抛出异常
                    throw new InterruptedException();
            }
        } finally {
            if (failed)// 9. 在整个获取中出错(比如线程中断/超时)
                cancelAcquire(node);// 10. 清除 node 节点(清除的过程是先给 node 打上 CANCELLED标志, 然后再删除)
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    /** acquire 是用于获取锁的最常用的模式
     * 步骤
     *      1. 调用 tryAcquire 尝试性的获取锁(一般都是又子类实现), 成功的话直接返回
     *      2. tryAcquire 调用获取失败, 将当前的线程封装成 Node 加入到 Sync Queue 里面(调用addWaiter), 等待获取 signal 信号
     *      3. 调用 acquireQueued 进行自旋的方式获取锁(有可能会 repeatedly blocking and unblocking)
     *      4. 根据acquireQueued的返回值判断在获取lock的过程中是否被中断, 若被中断, 则自己再中断一下(selfInterrupt)
     *
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    /**
     * 带中断的获取锁(被其他线程中断后就直接返回)
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())// 1. 判断线程是否被终止
            throw new InterruptedException();
        if (!tryAcquire(arg))// 2. 尝试性的获取锁
            doAcquireInterruptibly(arg);// 3. 获取锁不成功, 直接加入到 Sync Queue 里面(这里的加入操作在doAcquireInterruptibly里面)
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())// 1. 判断线程是否被终止
            throw new InterruptedException();
        return tryAcquire(arg) ||
            doAcquireNanos(arg, nanosTimeout);// 2. 尝试性的获取锁, 获取锁不成功, 直接加入到 Sync Queue 里面(这里的加入操作在doAcquireNanos里面)
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {// 1. 调用子类, 若完全释放好, 则返回true(这里有lock重复获取)
            Node h = head;
            if (h != null && h.waitStatus != 0)// 2. h.waitStatus !=0 其实就是 h.waitStatus < 0 后继节点需要唤醒
                unparkSuccessor(h);// 3. 唤醒后继节点
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    /**
     * 获取 共享 lock
     * 1. 调用 tryAcquireShared 尝试性的获取锁(一般都是由子类实现), 成功的话直接返回
     * 2. tryAcquireShared 调用获取失败, 将当前的线程封装成 Node 加入到 Sync Queue 里面(调用addWaiter), 等待获取 signal 信号
     * 3. 在 Sync Queue 里面进行自旋的方式获取锁(有可能会 repeatedly blocking and unblocking
     * 4. 当获取失败, 则判断是否可以 block(block的前提是前继节点被打上 SIGNAL 标示)
     * 5. 共享与独占获取lock的区别主要在于 在共享方式下获取 lock 成功会判断是否需要继续唤醒下面的继续获取共享lock的节点(及方法 doReleaseShared)
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)// 1. 调用子类, 获取共享 lock  返回 < 0, 表示失败
            doAcquireShared(arg);// 2. 调用 doAcquireShared 当前 线程加入 Sync Queue 里面, 等待获取 lock
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    /**
     *  SyncQueue 里面是否有 node 节点
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    /**
     * 获取 lock 是否发生竞争
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    /**
     * Sync Queue 里面的有效的, 最前面的 node 节点
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    /**
     * Sync Queue 里面的有效的, 最前面的 node 节点
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        /**
         * 这里两次检测是怕线程 timeout 或 cancelled
         */
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    /**
     * 判断线程是否在 Sync Queue 里面
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    /**
     * 判断 Sync Queue 中等待获取 lock 的第一个 node 是否是 获取 writeLock 的(head 节点是已经获取 lock 的节点)
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    /**
     * 当前节点之前在 Sync Queue 里面是否有等待获取的 Node
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
            ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    /**
     * 获取 Sync Queue 里面 等待 获取 lock 的 长度
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    /**
     * 获取 Sync Queue 里面 等待 获取 lock 的 thread
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    /**
     * 获取 Sync Queue 里面 等待 获取 writeLock 的 thread
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    /**
     * 获取 Sync Queue 里面 等待 获取 readLock 的 thread
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    /**
     * 判断 node 是否在 Sync Queue 里面
     */
    final boolean isOnSyncQueue(Node node) {
        /**
         * 这里有点 tricky,
         * node.waitStatus == Node.CONDITION 则说明 node 一定在 Condition 里面
         * node.prev == null 说明 node 一定不在 Sync Queue 里面
         */
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        // node.next != null 则 node 一定在 Sync Queue; 但是反过来 在Sync Queue 里面的节点 不一定  node.next != null
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        /**
         * 因为这里存在 node 开始enq Sync Queue 的情形, 所以在此查找一下
         */
        return findNodeFromTail(node);
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    /**
     * 从 tail 开始查找 node
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    /**
     * 将 Node 从Condition Queue 转移到 Sync Queue 里面
     * 在调用transferForSignal之前, 会 first.nextWaiter = null;
     * 而我们发现 若节点是因为 timeout / interrupt 进行转移, 则不会进行这步操作; 两种情况的转移都会把 wautStatus 置为 0
     */
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */
        // 1. 若 node 已经 cancelled 则失败
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        Node p = enq(node);// 2. 加入 Sync Queue
        int ws = p.waitStatus;
        // 3. 这里的 ws > 0 指Sync Queue 中node 的前继节点cancelled 了, 所以, 唤醒一下 node ; compareAndSetWaitStatus(p, ws, Node.SIGNAL)失败, 则说明 前继节点已经变成 SIGNAL 或 cancelled, 所以也要 唤醒
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    /**
     * 在节点获取lock时被中断或获取超时才调用的转移方法
     * 将 Condition Queue 中因 timeout/interrupt 而唤醒的节点进行转移
     */
    final boolean transferAfterCancelledWait(Node node) {
        // 1. 没有 node 没有 cancelled , 直接进行转移 (转移后, Sync Queue , Condition Queue 都会存在 node)
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        // 2.这时是其他的线程发送signal,将本线程转移到 Sync Queue 里面的工程中(转移的过程中 waitStatus = 0了, 所以上面的 CAS 操作失败)
        while (!isOnSyncQueue(node))
            Thread.yield();// 这里调用 isOnSyncQueue判断是否已经 入Sync Queue 了
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    /**
     * condition 是否属于这个 AQS 的
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    /**
     * 这个 condition Queue 里面是否有等待的线程
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    /**
     * 这个 condition Queue 里面等待的线程的量
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    /**
     * 这个 condition Queue 里面等待的线程
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    /**
     * Condition Queue 是一个并发不安全的, 只用于独占模式的队列(PS: 为什么是并发不安全的呢? 主要是在操作 Condition 时, 线程必需获取 独占的 lock, 所以不需要考虑并发的安全问题);
     * 而当Node存在于 Condition Queue 里面, 则其只有 waitStatus, thread, nextWaiter 有值, 其他的都是null(其中的 waitStatus 只能是 CONDITION, 0(0 代表node进行转移到 Sync Queue里面, 或被中断/timeout)); 这里有个注意点, 就是 当线程被中断或获取 lock 超时, 则一瞬间 node 会存在于 Condition Queue, Sync Queue 两个队列中.
     *
     *
     * Condition实现等待的时候内部也有一个等待队列，等待队列是一个隐式的单向队列，
     * 等待队列中的每一个节点也是一个AbstractQueuedSynchronizer.Node实例。
     * 每个Condition对象中保存了firstWaiter和lastWaiter作为队列首节点和尾节点，
     * 每个节点使用Node.nextWaiter保存下一个节点的引用，因此等待队列是一个单向队列。
     * 每当一个线程调用Condition.await()方法，那么该线程会释放锁，构造成一个Node节点加入到等待队列的队尾。
     *
     *
     *
     * 总的来说，Condition的本质就是等待队列和同步队列的交互：
     * 当一个持有锁的线程调用Condition.await()时，它会执行以下步骤：
     * 1.构造一个新的等待队列节点加入到等待队列队尾
     * 2.释放锁，也就是将它的同步队列节点从同步队列队首移除
     * 3.自旋，直到它在等待队列上的节点移动到了同步队列（通过其他线程调用signal()）或被中断
     * 4.阻塞当前节点，直到它获取到了锁，也就是它在同步队列上的节点排队排到了队首。
     *
     * 当一个持有锁的线程调用Condition.signal()时，它会执行以下操作：
     * 从等待队列的队首开始，尝试对队首节点执行唤醒操作；如果节点CANCELLED，就尝试唤醒下一个节点；如果再CANCELLED则继续迭代。
     *
     * 对每个节点执行唤醒操作时，首先将节点加入同步队列，此时await()操作的步骤3的解锁条件就已经开启了。然后分两种情况讨论：
     * 1.如果先驱节点的状态为CANCELLED(>0) 或设置先驱节点的状态为SIGNAL失败，那么就立即唤醒当前节点对应的线程，此时await()方法就会完成步骤3，进入步骤4.
     * 2.如果成功把先驱节点的状态设置为了SIGNAL，那么就不立即唤醒了。等到先驱节点成为同步队列首节点并释放了同步状态后，会自动唤醒当前节点对应线程的，这时候await()的步骤3才执行完成，而且有很大概率快速完成步骤4.
     *
     * https://www.jianshu.com/p/52089c4eefdd
     * https://www.cnblogs.com/sheeva/p/6484224.html
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. */
        /** Condition Queue 里面的头节点 */
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        /** Condition Queue 里面的尾节点 */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         * @return its new wait node
         * 将当前线程封装成一个Node节点放入到Condition Queue里面
         * 大家可以注意到, 下面对Condition Queue 的操作都没考虑到并发(Sync Queue 的队列是支持并发操作的), 这是为什么呢?
         * 因为在进行操作 Condition队列 是当前的线程已经获取了AQS的独占锁, 所以不需要考虑并发的情况
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter; // 1. Condition queue 的尾节点

            // If lastWaiter is cancelled, clean out.
            // 2.尾节点已经Cancel, 直接进行清除，这里有1个问题, 何时出现t.waitStatus != Node.CONDITION -> 在对线程进行中断时 ConditionObject -> await -> checkInterruptWhileWaiting -> transferAfterCancelledWait "compareAndSetWaitStatus(node, Node.CONDITION, 0)" <- 导致这种情况一般是 线程中断或 await 超时
            // 一个注意点: 当Condition进行 awiat 超时或被中断时, Condition里面的节点是没有被删除掉的, 需要其他 await 在将线程加入 Condition Queue 时调用addConditionWaiter而进而删除, 或 await 操作差不多结束时, 调用 "node.nextWaiter != null" 进行判断而删除 (PS: 通过 signal 进行唤醒时 node.nextWaiter 会被置空, 而中断和超时时不会)
            if (t != null && t.waitStatus != Node.CONDITION) {
                // 3. 调用 unlinkCancelledWaiters 对 "waitStatus != Node.CONDITION" 的节点进行删除(在Condition里面的Node的waitStatus 要么是CONDITION(正常), 要么就是 0 (signal/timeout/interrupt))
                unlinkCancelledWaiters();
                // 4. 获取最新的 lastWaiter
                t = lastWaiter;
            }
            // 5. 将线程封装成 node 准备放入 Condition Queue 里面
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                // 6 .Condition Queue 是空的
                firstWaiter = node;
            else
                t.nextWaiter = node;// 7. 最加到 queue 尾部
            lastWaiter = node;// 8. 重新赋值 lastWaiter
            return node;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         *
         *
         *唤醒 Condition Queue 里面的头节点, 注意这里的唤醒只是将 Node 从 Condition Queue 转到 Sync Queue 里面(这时的 Node 也还是能被 Interrupt)
         *
         *
         */
        private void doSignal(Node first) {
            do {
                // 1. 将 first.nextWaiter 赋值给 firstWaiter 为下次做准备
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;// 2. 这时若 nextWaiter == null, 则说明 Condition 为空了, 所以直接置空 lastWaiter
                first.nextWaiter = null; // 3.  first.nextWaiter == null 是判断 Node 从 Condition queue 转移到 Sync Queue 里面是通过 signal 还是 timeout/interrupt
            } while (!transferForSignal(first) &&
                     (first = firstWaiter) != null);// 4. 调用  transferForSignal将 first 转移到 Sync Queue 里面, 返回不成功的话, 将 firstWaiter 赋值给 first
        }

        /**
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue
         *
         * 唤醒 Condition Queue 里面的所有的节点
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;// 1. 将 lastWaiter, firstWaiter 置空
            do {
                Node next = first.nextWaiter;// 2. 初始化下个换新的节点
                first.nextWaiter = null;// 3.  first.nextWaiter == null 是判断 Node 从 Condition queue 转移到 Sync Queue 里面是通过 signal 还是 timeout/interrupt
                transferForSignal(first);// 4. 调用  transferForSignal将 first 转移到 Sync Queue 里面
                first = next; // 5. 开始换新 next 节点
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         */
        /**
         * 在调用 addConditionWaiter 将线程放入 Condition Queue 里面时 或 awiat 方法获取差不多结束时
         * 进行清理 Condition queue 里面的因 timeout/interrupt 而还存在的节点，这个删除操作比较巧妙,
         * 其中引入了 trail 节点，可以理解为traverse整个Condition Queue 时遇到的最后一个有效的节点
         *
         * 毫无疑问, 这是一段非常精巧的queue节点删除, 主要还是在 节点 trail 上, trail 节点可以理解为traverse整个 Condition Queue 时遇到的最后一个有效的节点
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            while (t != null) {
                Node next = t.nextWaiter;// 1. 先初始化 next 节点
                if (t.waitStatus != Node.CONDITION) {// 2. 节点不有效, 在Condition Queue 里面 Node.waitStatus 只有可能是 CONDITION 或是 0(timeout/interrupt引起的)
                    t.nextWaiter = null;// 3. Node.nextWaiter 置空
                    if (trail == null)// 4. 一次都没有遇到有效的节点
                        firstWaiter = next;// 5. 将 next 赋值给 firstWaiter(此时 next 可能也是无效的, 这只是一个临时处理)
                    else
                        trail.nextWaiter = next;// 6. next 赋值给 trail.nextWaiter, 这一步其实就是删除节点 t
                    if (next == null)
                        lastWaiter = trail;// 7. next == null 说明 已经 traverse 完了 Condition Queue
                }
                else
                    trail = t;// 8. 将有效节点赋值给 trail
                t = next;
            }
        }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *  将 Condition queue 的头节点转移到 Sync Queue 里面
         *  在进行调用 signal 时, 当前的线程必须获取了 独占的锁
         */
        public final void signal() {
            // 1. 判断当前的线程是否已经获取 独占锁
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);// 2. 调用 doSignal 进行转移
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         *
         * 将 Condition Queue 里面的节点都转移到 Sync Queue 里面
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         * 不响应线程中断的方式进行 await
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();// 1. 将当前线程封装成一个 Node 放入 Condition Queue 里面
            int savedState = fullyRelease(node);// 2. 释放当前线程所获取的所有的独占锁(PS: 独占的锁支持重入), 等等, 为什么要释放呢? 以为你调用 awaitUninterruptibly 方法的前提就是你已经获取了 独占锁
            boolean interrupted = false;// 3. 线程中断标识
            while (!isOnSyncQueue(node)) {// 4. 这里是一个 while loop, 调用 isOnSyncQueue 判断当前的 Node 是否已经被转移到 Sync Queue 里面
                LockSupport.park(this);// 5. 若当前 node 不在 sync queue 里面, 则先 block 一下等待其他线程调用 signal 进行唤醒; (这里若有其他线程对当前线程进行 中断的换, 也能进行唤醒)
                if (Thread.interrupted())// 6. 判断这是唤醒是 signal 还是 interrupted(Thread.interrupted()会清楚线程的中断标记, 但没事, 我们有步骤7中的interrupted进行记录)
                    interrupted = true;// 7. 说明这次唤醒是被中断而唤醒的,这个标记若是true的话, 在 awiat 离开时还要 自己中断一下(selfInterrupt), 其他的函数可能需要线程的中断标识
            }
            if (acquireQueued(node, savedState) || interrupted)// 8. acquireQueued 返回 true 说明线程在 block 的过程中式被 inetrrupt 过(其实 acquireQueued 返回 true 也有可能其中有一次唤醒是 通过 signal)
                selfInterrupt();// 9. 自我中断, 外面的线程可以通过这个标识知道, 整个 awaitUninterruptibly 运行过程中 是否被中断过
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         * 下面两个是用于追踪 调用 awaitXXX 方法时线程有没有被中断过
         * 主要的区别是
         *      REINTERRUPT: 代表线程是在 signal 后被中断的        (REINTERRUPT = re-interrupt 再次中断 最后会调用 selfInterrupt)
         *      THROW_IE: 代表在接受 signal 前被中断的, 则直接抛出异常 (Throw_IE = throw inner exception)
         */

        /** Mode meaning to reinterrupt on exit from wait */
        // 在离开 awaitXX方法, 退出前再次 自我中断 (调用 selfInterrupt)
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        // 在离开 awaitXX方法, 退出前再次, 以为在 接受 signal 前被中断, 所以需要抛出异常
        private static final int THROW_IE    = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        /**
         * 检查 在 awaitXX 方法中的这次唤醒是否是中断引起的
         * 若是中断引起的, 则将 Node 从 Condition Queue 转移到 Sync Queue 里面
         * 返回值的区别:
         *      0: 此次唤醒是通过 signal -> LockSupport.unpark
         *      THROW_IE: 此次的唤醒是通过 interrupt, 并且 在 接受 signal 之前
         *      REINTERRUPT: 线程的唤醒是 接受过 signal 而后又被中断
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        /**
         * 这个方法是在 awaitXX 方法离开前调用的, 主要是根据
         * interrupMode 判断是抛出异常, 还是自我再中断一下
         */
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        /**
         * 支持 InterruptedException 的 await <- 注意这里即使是线程被中断,
         * 还是需要获取了独占的锁后, 再 调用 lock.unlock 进行释放锁
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())// 1. 判断线程是否中断
                throw new InterruptedException();
            Node node = addConditionWaiter();// 2. 将线程封装成一个 Node 放到 Condition Queue 里面, 其中可能有些清理工作
            int savedState = fullyRelease(node);// 3. 释放当前线程所获取的所有的锁 (PS: 调用 await 方法时, 当前线程是必须已经获取了独占的锁)
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {// 4. 判断当前线程是否在 Sync Queue 里面(这里 Node 从 Condtion Queue 里面转移到 Sync Queue 里面有两种可能 (1) 其他线程调用 signal 进行转移 (2) 当前线程被中断而进行Node的转移(就在checkInterruptWhileWaiting里面进行转移))
                LockSupport.park(this);// 5. 当前线程没在 Sync Queue 里面, 则进行 block
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)// 6. 判断此次线程的唤醒是否因为线程被中断, 若是被中断, 则会在checkInterruptWhileWaiting的transferAfterCancelledWait 进行节点的转移; 返回值 interruptMode != 0
                    break;                                                  // 说明此是通过线程中断的方式进行唤醒, 并且已经进行了 node 的转移, 转移到 Sync Queue 里面
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)// 7. 调用 acquireQueued在 Sync Queue 里面进行 独占锁的获取, 返回值表明在获取的过程中有没有被中断过
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // clean up if cancelled  // 8. 通过 "node.nextWaiter != null" 判断 线程的唤醒是中断还是 signal, 因为通过中断唤醒的话, 此刻代表线程的 Node 在 Condition Queue 与 Sync Queue 里面都会存在
                unlinkCancelledWaiters();// 9. 进行 cancelled 节点的清除
            if (interruptMode != 0)// 10. "interruptMode != 0" 代表通过中断的方式唤醒线程
                reportInterruptAfterWait(interruptMode);// 11. 根据 interruptMode 的类型决定是抛出异常, 还是自己再中断一下
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        /**
         * 所有 awaitXX 方法其实就是
         *  0. 将当前的线程封装成 Node 加入到 Condition 里面
         *  1. 丢到当前线程所拥有的 独占锁,
         *  2. 等待 其他获取 独占锁的线程的唤醒, 唤醒从 Condition Queue 到 Sync Queue 里面, 进而获取 独占锁
         *  3. 最后获取 lock 之后, 在根据线程唤醒的方式(signal/interrupt) 进行处理
         *  4. 最后还是需要调用 lock./unlock 进行释放锁
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted()) // 1. 判断线程是否中断
                throw new InterruptedException();
            Node node = addConditionWaiter(); // 2. 将线程封装成一个 Node 放到 Condition Queue 里面, 其中可能有些清理工作
            int savedState = fullyRelease(node);// 3. 释放当前线程所获取的所有的锁 (PS: 调用 await 方法时, 当前线程是必须已经获取了独占的锁)
            final long deadline = System.nanoTime() + nanosTimeout; // 4. 计算 wait 的截止时间
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {// 5. 判断当前线程是否在 Sync Queue 里面(这里 Node 从 Condtion Queue 里面转移到 Sync Queue 里面有两种可能 (1) 其他线程调用 signal 进行转移 (2) 当前线程被中断而进行Node的转移(就在checkInterruptWhileWaiting里面进行转移))
                if (nanosTimeout <= 0L) {// 6. 等待时间超时(这里的 nanosTimeout 是有可能 < 0),
                    transferAfterCancelledWait(node);//  7. 调用 transferAfterCancelledWait 将 Node 从 Condition 转移到 Sync Queue 里面
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)// 8. 当剩余时间 < spinForTimeoutThreshold, 其实函数 spin 比用 LockSupport.parkNanos 更高效
                    LockSupport.parkNanos(this, nanosTimeout);// 9. 进行线程的 block
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)// 10. 判断此次线程的唤醒是否因为线程被中断, 若是被中断, 则会在checkInterruptWhileWaiting的transferAfterCancelledWait 进行节点的转移; 返回值 interruptMode != 0
                    break;// 说明此是通过线程中断的方式进行唤醒, 并且已经进行了 node 的转移, 转移到 Sync Queue 里面
                nanosTimeout = deadline - System.nanoTime();  // 11. 计算剩余时间
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)// 12. 调用 acquireQueued在 Sync Queue 里面进行 独占锁的获取, 返回值表明在获取的过程中有没有被中断过
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // 13. 通过 "node.nextWaiter != null" 判断 线程的唤醒是中断还是 signal, 因为通过中断唤醒的话, 此刻代表线程的 Node 在 Condition Queue 与 Sync Queue 里面都会存在
                unlinkCancelledWaiters();// 14. 进行 cancelled 节点的清除
            if (interruptMode != 0)// 15. "interruptMode != 0" 代表通过中断的方式唤醒线程
                reportInterruptAfterWait(interruptMode);// 16. 根据 interruptMode 的类型决定是抛出异常, 还是自己再中断一下
            return deadline - System.nanoTime(); // 17 这个返回值代表是 通过 signal 还是 超时
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        /**
         * 所有 awaitXX 方法其实就是
         *  0. 将当前的线程封装成 Node 加入到 Condition 里面
         *  1. 丢到当前线程所拥有的 独占锁,
         *  2. 等待 其他获取 独占锁的线程的唤醒, 唤醒从 Condition Queue 到 Sync Queue 里面, 进而获取 独占锁
         *  3. 最后获取 lock 之后, 在根据线程唤醒的方式(signal/interrupt) 进行处理
         *  4. 最后还是需要调用 lock./unlock 进行释放锁
         *
         *  awaitUntil 和 awaitNanos 差不多
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())// 1. 判断线程是否中断
                throw new InterruptedException();
            Node node = addConditionWaiter();// 2. 将线程封装成一个 Node 放到 Condition Queue 里面, 其中可能有些清理工作
            int savedState = fullyRelease(node);// 3. 释放当前线程所获取的所有的锁 (PS: 调用 await 方法时, 当前线程是必须已经获取了独占的锁)
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {// 4. 判断当前线程是否在 Sync Queue 里面(这里 Node 从 Condtion Queue 里面转移到 Sync Queue 里面有两种可能 (1) 其他线程调用 signal 进行转移 (2) 当前线程被中断而进行Node的转移(就在checkInterruptWhileWaiting里面进行转移))
                if (System.currentTimeMillis() > abstime) { // 5. 计算是否超时
                    timedout = transferAfterCancelledWait(node);//  6. 调用 transferAfterCancelledWait 将 Node 从 Condition 转移到 Sync Queue 里面
                    break;
                }
                LockSupport.parkUntil(this, abstime);// 7. 进行 线程的阻塞
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)// 8. 判断此次线程的唤醒是否因为线程被中断, 若是被中断, 则会在checkInterruptWhileWaiting的transferAfterCancelledWait 进行节点的转移; 返回值 interruptMode != 0
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)// 9. 调用 acquireQueued在 Sync Queue 里面进行 独占锁的获取, 返回值表明在获取的过程中有没有被中断过
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)// 10. 通过 "node.nextWaiter != null" 判断 线程的唤醒是中断还是 signal, 因为通过中断唤醒的话, 此刻代表线程的 Node 在 Condition Queue 与 Sync Queue 里面都会存在
                unlinkCancelledWaiters(); // 11. 进行 cancelled 节点的清除
            if (interruptMode != 0)// 12. "interruptMode != 0" 代表通过中断的方式唤醒线程
                reportInterruptAfterWait(interruptMode);// 13. 根据 interruptMode 的类型决定是抛出异常, 还是自己再中断一下
            return !timedout;// 14. 返回是否通过 interrupt 进行线程的唤醒
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        /**判断当前 condition 是否获取 独占锁 */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        /**
         * 查看 Condition Queue 里面是否有 CONDITION 的节点
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        /**
         * 获取 Condition queue 里面的  CONDITION 的节点的个数
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        /**
         * 获取 等待的线程
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}

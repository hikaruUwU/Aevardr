package project.sekai;

import java.lang.ref.Cleaner;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * QuotaManager
 * <p>
 * Quota Manager with Builder pattern.
 */
public class QuotaManager<T> {
    private final AtomicLong Repository = new AtomicLong(0x8000000000000000L);

    private final long EXPIRE_TIME;

    private volatile boolean DESTROY_PHASE = false;

    private final Consumer<T> EXPIRE_BEHAVIOR;

    private final ScheduledExecutorService Scheduler;

    private final Map<T, ScheduledFuture<?>> ExpirationLedger = new ConcurrentHashMap<>();

    private RateLimiter rateLimiter;

    private boolean ENABLE_RATE_LIMIT = false;

    /**
     * Private Constructor
     */
    private QuotaManager(Builder<T> builder) {
        this.EXPIRE_TIME = builder.expireTime;
        this.Repository.set(builder.initialCap);
        this.EXPIRE_BEHAVIOR = builder.expireBehavior;
        this.Scheduler = builder.scheduler;

        if (builder.limiter != null) {
            this.rateLimiter = builder.limiter;
            ENABLE_RATE_LIMIT = true;
        }

        Cleaner.create().register(this, Scheduler::shutdownNow);
    }

    /**
     * Stop this when all the expiration-timers and work are done.
     *
     * @return remaining unused quotas.
     */
    public long stop() {
        DESTROY_PHASE = true;
        Scheduler.shutdown();
        expireAllUnconfirmed();
        return Repository.get();
    }

    /**
     * Stop this now no matter the expiration-timers is not finished.
     *
     * @return remaining unused quotas.
     */
    public long ForceStop() throws InterruptedException {
        DESTROY_PHASE = true;
        Scheduler.shutdownNow();
        expireAllUnconfirmed();
        return Repository.get();
    }

    /**
     * Take one quota and attach your relative data or id to it.
     * Won't accept any quota request when shutting down.
     *
     * @param relativeData Data/ID relative to this quota.
     * @return Quota's serial number, empty when out of limit or service is shutting down.
     */
    public Optional<Long> take(T relativeData) {
        if (DESTROY_PHASE || (ENABLE_RATE_LIMIT && !rateLimiter.tryAcquire()))
            return Optional.empty();

        long l = Repository.getAndUpdate(cur -> cur > 0 ? cur - 1 : cur);
        if (l > 0) {
            ExpirationLedger.put(relativeData, Scheduler.schedule(() -> {
                Repository.incrementAndGet();
                EXPIRE_BEHAVIOR.accept(relativeData);
                ExpirationLedger.remove(relativeData);
            }, EXPIRE_TIME, TimeUnit.MILLISECONDS));
            return Optional.of(l);
        } else
            return Optional.empty();
    }

    /**
     * Cancel your quota before expired.Don't confirm before cancelling.
     *
     * @param t Data/ID instance relative to your quota
     * @return {@code True} - succeeded.
     * {@code False} - failed, your quota may have been cancelled once before / service is shutting down.
     */
    public boolean cancelled(T t) {
        if (!DESTROY_PHASE && ExpirationLedger.remove(t) != null) {
            Repository.incrementAndGet();
            return true;
        } else
            return false;
    }

    /**
     * Confirm your quota, prevent your quota from expiration process.
     *
     * @param t Data/ID instance relative to your quota
     * @return {@code True} - succeeded.
     * {@code False} - failed, your quota may have been confirmed before / service is shutting down.
     */
    public boolean confirmed(T t) {
        return !DESTROY_PHASE && ExpirationLedger.remove(t) != null;
    }

    /**
     * Expire all the unconfirmed quotas.
     */
    public void expireAllUnconfirmed() {
        ExpirationLedger.values().forEach(task -> task.cancel(false));
    }

    /**
     * Get Remaining quota,estimated value
     *
     * @return remaining quota
     */
    public long watchOpaque() {
        return Repository.getOpaque();
    }

    /**
     * Get Remaining quota accurately.
     *
     * @return remaining quota
     */
    public long watchExact() {
        return Repository.get();
    }

    public static <T> Builder<T> getBuilder() {
        return new Builder<>();
    }

    /**
     * Builder for QuotaManager.
     *
     * @param <T> the type of data that will assign to quota.
     */
    public static class Builder<T> {
        private long initialCap = 0x8000000000000000L;
        private long expireTime = 60;
        private int workerCore = Runtime.getRuntime().availableProcessors();
        private Consumer<T> expireBehavior = _ -> {
        };
        private ThreadFactory threadType = Thread.ofPlatform().factory();
        private ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(workerCore, threadType);

        private RateLimiter limiter;

        public Builder<T> rateLimit(RateLimiter rateLimiter) {
            this.limiter = rateLimiter;
            return this;
        }

        /**
         * Set the total amount of  quotas.
         *
         * @param initialCap amount.
         */
        public Builder<T> initialCap(long initialCap) {
            this.initialCap = initialCap;
            return this;
        }

        /**
         * Set when token quotas are expired.
         *
         * @param expireTime millisecond unit.
         */
        public Builder<T> expireTime(long expireTime) {
            this.expireTime = expireTime;
            return this;
        }

        /**
         * Set the ExpirationWorkerScheduler 's core number.
         *
         * @param workerCore workCore.
         */
        public Builder<T> workerCore(int workerCore) {
            this.workerCore = workerCore;
            this.scheduler = new ScheduledThreadPoolExecutor(workerCore, Thread.ofVirtual().factory());
            return this;
        }

        /**
         * enable for using VirtualThread ,requiring java version >= 21
         *
         * @param enable decide if using VirtualThread for scheduler.
         */
        public Builder<T> virtualThreadWorker(boolean enable) {
            if (enable)
                this.threadType = Thread.ofVirtual().factory();
            return this;
        }

        /**
         * set the behavior after a quota expired.
         *
         * @param expireBehavior this function will be accepted after a quota expired.
         */
        public Builder<T> expireBehavior(Consumer<T> expireBehavior) {
            this.expireBehavior = expireBehavior;
            return this;
        }

        public QuotaManager<T> build() {
            return new QuotaManager<>(this);
        }
    }
}

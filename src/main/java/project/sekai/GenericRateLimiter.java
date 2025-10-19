package project.sekai;

import java.lang.ref.Cleaner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class GenericRateLimiter implements RateLimiter {
    private static final Cleaner cleaner = Cleaner.create();

    private final boolean HIGH_TRAFFIC;
    private final Semaphore primary;
    private Semaphore[] secondary;
    private final long TIMEOUT;
    private final Thread worker;

    private final List<Thread> parkedThreads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean parkSignal = new AtomicBoolean(false);
    private volatile boolean running = true;

    private GenericRateLimiter(Builder builder) {
        this.HIGH_TRAFFIC = builder.HIGH_TRAFFIC_MODE;
        this.TIMEOUT = builder.TIMEOUT;

        if (!HIGH_TRAFFIC) {
            this.primary = new Semaphore(builder.CAPACITY);
        } else {
            int base = builder.CAPACITY / 3;
            this.primary = new Semaphore(base);
            this.secondary = new Semaphore[]{
                    new Semaphore(base),
                    new Semaphore(base / 3 + builder.CAPACITY % 3)
            };
        }

        Runnable work = () -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                parkSignal.set(true);
                reloadPermits(builder);

                try {
                    Thread.sleep(builder.DELAY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                parkSignal.set(false);
                unparkLimited();
            }
            unparkLimited();
            parkSignal.set(false);
        };

        this.worker = (builder.Virtual ? Thread.ofVirtual() : Thread.ofPlatform())
                .name("GenericRateLimiter-Worker")
                .start(work);

        cleaner.register(this, this::stop);
    }

    private void reloadPermits(Builder builder) {
        if (HIGH_TRAFFIC) {
            int base = builder.CAPACITY / 3;
            reset(primary, base);
            reset(secondary[0], base);
            reset(secondary[1], base);
        } else {
            reset(primary, builder.CAPACITY);
        }
    }

    private void reset(Semaphore s, int n) {
        s.drainPermits();
        s.release(n);
    }

    private void unparkLimited() {
        int wakeCount = Math.min(primary.availablePermits(), parkedThreads.size());
        for (int i = 0; i < wakeCount; i++)
            LockSupport.unpark(parkedThreads.get(i));
    }

    private boolean dispatcher() throws InterruptedException {
        if (!HIGH_TRAFFIC)
            return primary.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS);
        int idx = ThreadLocalRandom.current().nextBoolean() ? 0 : 1;
        return secondary[idx].tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean tryAcquire() {
        if (parkSignal.get()) {
            Thread t = Thread.currentThread();
            parkedThreads.add(t);
            try {
                LockSupport.park();
            } finally {
                parkedThreads.remove(t);
            }
        }

        try {
            return dispatcher();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public int[] getAvailableCount() {
        return new int[]{
                primary.availablePermits(),
                (secondary != null ? secondary[0].availablePermits() : 0),
                (secondary != null ? secondary[1].availablePermits() : 0)
        };
    }

    public void stop() {
        running = false;
        worker.interrupt();
    }

    public static Builder getBuilder() {
        return new Builder();
    }

    public static class Builder {
        private boolean HIGH_TRAFFIC_MODE = false;

        private int CAPACITY = 10;

        private long DELAY = 1000;

        private long TIMEOUT = 500;

        private boolean Virtual = false;

        /**
         * Enable high traffic mode will dispatch the permit-requests to 3 sub semaphore.
         * If your initial capacity is small and enable this,may cause thread awaiting frequently.
         *
         * @param enable Using high traffic mode or not,default false.
         */
        public Builder HIGH_TRAFFIC_MODE(boolean enable) {
            this.HIGH_TRAFFIC_MODE = enable;
            return this;
        }

        /**
         * This option controls the time if permit-tokens are out,the blocking threads' awaiting time.
         * Default : 500 ms
         *
         * @param timeout MilliSecond Unit.
         */
        public Builder setTimeout(long timeout) {
            this.TIMEOUT = timeout;
            return this;
        }

        /**
         * Using this to enable VirtualThread.Java version >= 21 required.
         * Default : PlatForm Thread.
         */
        public Builder ofVirtual() {
            this.Virtual = true;
            return this;
        }

        /**
         * Set the interval between reloading permissions.
         * Default : 1s
         *
         * @param Mtime MilliSecond Unit.
         */
        public Builder setInterval(long Mtime) {
            this.DELAY = Mtime;
            return this;
        }

        /**
         * permission amount.
         * Default : 10
         */
        public Builder CapacityPerSec(int capacity) {
            this.CAPACITY = capacity;
            return this;
        }

        public GenericRateLimiter build() {
            return new GenericRateLimiter(this);
        }
    }
}
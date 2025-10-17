package project.sekai;

import java.lang.ref.Cleaner;
import java.util.concurrent.*;

public class GenericRateLimiter implements RateLimiter {
    private final boolean HIGH_TRAFFIC;

    private final Semaphore primary;

    private Semaphore[] secondary;

    private final long TIMEOUT;

    private final Thread worker;

    private GenericRateLimiter(Builder builder) {
        this.HIGH_TRAFFIC = builder.HIGH_TRAFFIC_MODE;
        this.TIMEOUT = builder.TIMEOUT;

        if (!HIGH_TRAFFIC) {
            this.primary = new Semaphore(builder.CAPACITY);
        } else {
            int basement = builder.CAPACITY / 3;
            this.primary = new Semaphore(basement);
            secondary = new Semaphore[2];
            secondary[0] = new Semaphore(basement);
            secondary[1] = new Semaphore(basement / 3 + builder.CAPACITY % 3);
        }

        Runnable work = () -> {
            while(true){
                if (HIGH_TRAFFIC) {
                    int basement = builder.CAPACITY / 3;
                    this.primary.drainPermits();
                    primary.release(basement);
                    secondary[0].drainPermits();
                    secondary[0].release(basement);
                    secondary[1].drainPermits();
                    secondary[1].release(basement);
                } else {
                    this.primary.drainPermits();
                    primary.release(builder.CAPACITY);
                }
                try {
                    Thread.sleep(builder.DELAY);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        };

        if (builder.Virtual)
            worker = Thread.ofVirtual().start(work);
        else
            worker = Thread.ofPlatform().start(work);

        Cleaner.create().register(this, worker::interrupt);
    }

    private boolean dispatcher() {
        try {
            if (!HIGH_TRAFFIC)
                return primary.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS);
            else
                return secondary[ThreadLocalRandom.current().nextBoolean() ? 0 : 1].tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            return false;
        }
    }

    /**
     * Watch the available permission in each bucket.
     *
     * @return {@code int[a,b,c]},a:primary permission bucket; b,c:secondary permission bucket,only using when HIGH_TRAFFIC_MODE on.
     */
    public int[] getAvailableCount() {
        return new int[]{
                primary.availablePermits(),
                (secondary != null) ? secondary[0].availablePermits() : 0,
                (secondary != null) ? secondary[1].availablePermits() : 0
        };
    }

    /**
     * Acquire one permission.
     *
     * @return {@code true}-When permitted, {@code false} when not permitted.
     */
    @Override
    public boolean tryAcquire() {
        try {
            return dispatcher();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
        return false;
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
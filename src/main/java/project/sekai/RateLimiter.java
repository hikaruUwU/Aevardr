package project.sekai;

public interface RateLimiter {
    /**
     * Acquire one permission.
     *
     * @return {@code true}-When permitted, {@code false} when not permitted.
     */
    boolean tryAcquire();
}
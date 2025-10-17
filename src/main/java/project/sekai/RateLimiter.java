package project.sekai;

public interface RateLimiter {
    boolean tryAcquire();
}

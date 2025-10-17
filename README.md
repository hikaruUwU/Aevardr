QuotaManager

Overview

The QuotaManager is a resource management system that allows you to manage quotas for tasks with support for expiration, rate limiting, and custom behaviors upon expiration. The system uses a builder pattern for easy configuration.

Key Features

Quota Management: Keep track of quotas and allow tasks to take and confirm quotas.

Expiration Support: Set expiration time for quotas with customizable behaviors when they expire.

Rate Limiting: Optional rate limiting using the GenericRateLimiter.

Graceful Shutdown: Allows the system to stop safely, ensuring that expired quotas are properly handled.

Main Components

QuotaManager<T>: The core class managing quotas.

GenericRateLimiter: A rate-limiting implementation that can be configured for high traffic scenarios.

RateLimiter: The interface for rate-limiting logic.

Usage

To use QuotaManager, you must build it using the Builder class. Here's an example:

QuotaManager<String> manager = QuotaManager.<String>getBuilder()
    .initialCap(100)
    .expireTime(5000)
    .rateLimit(new GenericRateLimiter.Builder().CapacityPerSec(10).setTimeout(1000).build())
    .expireBehavior(s -> System.out.println(s + " expired"))
    .build();

Optional<Long> quota = manager.take("task1");
if (quota.isPresent()) {
    System.out.println("Quota taken: " + quota.get());
}

Main Methods

take(T relativeData): Take one quota and attach data to it. Returns an Optional<Long> with the quota serial number.

cancelled(T t): Cancel the quota before it expires.

confirmed(T t): Confirm that the quota is still in use and prevent expiration.

stop(): Safely stop the QuotaManager.

ForceStop(): Forcibly stop the QuotaManager without waiting for all expiration timers to finish.

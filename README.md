# Personal_QuotaManager

### Overview

The **QuotaManager** is a resource management system that allows you to manage quotas for tasks with support for expiration, rate limiting, and custom behaviors upon expiration. The system uses a builder pattern for easy configuration.

### Key Features

- **Quota Management**: Keep track of quotas and allow tasks to take and confirm quotas.
- **Expiration Support**: Set expiration time for quotas with customizable behaviors when they expire.
- **Rate Limiting**: Optional rate limiting using the `GenericRateLimiter`.
- **Graceful Shutdown**: Allows the system to stop safely, ensuring that expired quotas are properly handled.
- **Quota⇌Data Binding**: Allows you binding any identifier class to identify the token quota.

### Main Components

- `QuotaManager<T>`: The core class managing quotas.
- `GenericRateLimiter`: A rate-limiting implementation that can be configured for high traffic scenarios.
- `RateLimiter`: The interface for rate-limiting logic.

### Usage

To use `QuotaManager`, you must build it using the `Builder` class. Here's an example:

```java
QuotaManager<Integer> manager = QuotaManager.<Integer>getBuilder()
    .initialCap(100)
    .expireTime(5000)
    .rateLimit(new GenericRateLimiter.Builder().CapacityPerSec(10).setTimeout(1000).build())
    .expireBehavior(s -> System.out.println(s + " expired"))
    .build();
    
//Generic T : the identifier class type.

Optional<Long> quota = manager.take(114514);
if (quota.isPresent()) {
    System.out.println("Quota taken: " + quota.get());
}

//If you need return the quota.
manager.cancelled(quota);

//Don't have to save the quota object,you can reassemble T class with overrided equals() & hash().
manager.cancelled(114514);

//Confirm to prevent quota from expiration.
manager.confirmed(quota);

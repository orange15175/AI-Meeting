package com.hewei.hzyjy.aimeeting.common.ratelimit;

/**
 * Effective per-bucket rate-limit policy.
 */
public record RequestRateLimitPolicy(
        String bucketName,
        long maxAccessCount,
        long timeWindowSeconds,
        long requestedTokens) {
}

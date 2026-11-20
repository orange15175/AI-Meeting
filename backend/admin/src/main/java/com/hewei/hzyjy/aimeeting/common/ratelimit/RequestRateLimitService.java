package com.hewei.hzyjy.aimeeting.common.ratelimit;

public interface RequestRateLimitService {

    boolean tryAcquire(String key, RequestRateLimitPolicy policy);
}

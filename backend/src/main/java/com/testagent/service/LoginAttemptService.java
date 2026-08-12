package com.testagent.service;

import com.testagent.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v4.1: 登录防爆破——内存计数，5 次失败锁定 5 分钟。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MS = 5 * 60_000L;

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    public void checkLocked(String username) {
        Long until = lockUntil.get(username);
        if (until == null) return;
        if (System.currentTimeMillis() < until) {
            long remainSec = (until - System.currentTimeMillis()) / 1000;
            long remainMin = Math.max(1, (remainSec + 59) / 60);
            throw new BusinessException(40104,
                    "登录失败次数过多，请 " + remainMin + " 分钟后再试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        lockUntil.remove(username);
        attempts.remove(username);
    }

    public void loginFailed(String username) {
        AtomicInteger counter = attempts.computeIfAbsent(username, k -> new AtomicInteger());
        if (counter.incrementAndGet() >= MAX_ATTEMPTS) {
            lockUntil.put(username, System.currentTimeMillis() + LOCK_DURATION_MS);
        }
    }

    public void loginSucceeded(String username) {
        attempts.remove(username);
        lockUntil.remove(username);
    }
}

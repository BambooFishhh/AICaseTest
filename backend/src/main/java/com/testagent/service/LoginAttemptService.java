package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.runtime.RuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * v4.1: 登录防爆破——5 次失败锁定 5 分钟。
 * v5.2: 计数与锁定状态迁至 RuntimeStore（Redis/内存）。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MS = 5 * 60_000L;

    @Autowired
    private RuntimeStore runtimeStore;

    public void checkLocked(String username) {
        long until = runtimeStore.getLockUntil(username);
        if (until < 0) return;
        if (System.currentTimeMillis() < until) {
            long remainSec = (until - System.currentTimeMillis()) / 1000;
            long remainMin = Math.max(1, (remainSec + 59) / 60);
            throw new BusinessException(40104,
                    "登录失败次数过多，请 " + remainMin + " 分钟后再试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        runtimeStore.clearLogin(username);
    }

    public void loginFailed(String username) {
        if (runtimeStore.incrementLoginAttempts(username) >= MAX_ATTEMPTS) {
            runtimeStore.setLockUntil(username, System.currentTimeMillis() + LOCK_DURATION_MS);
        }
    }

    public void loginSucceeded(String username) {
        runtimeStore.clearLogin(username);
    }
}

package com.hewei.hzyjy.aimeeting.toolkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * 线程相关工具类
 *
 * @Author: Administrator
 **/
public class Threads {
    private static final Logger logger = LoggerFactory.getLogger(Threads.class);

    /**
     * sleep等待,单位为毫秒
     * @param milliseconds 毫秒时间
     */
    public static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            return;
        }
    }

    /**
     * 打印线程异常信息
     * @param r 线程
     * @param t 异常
     */
    public static void printException(Runnable r, Throwable t) {
        if (t == null && r instanceof Future<?>) {
            try {
                Future<?> future = (Future<?>) r;
                if (future.isDone() && !future.isCancelled()) {
                    future.get();
                }
            } catch (CancellationException ce) {
                return;
            } catch (ExecutionException ee) {
                t = ee.getCause();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (t != null) {
            logger.error(t.getMessage(), t);
        }
    }
}

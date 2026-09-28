package com.github.lisama569.settlementledgerjava.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 归档批次故障注入的持有器：默认无故障；仅测试通过包可见构造 {@link ArchiveServiceImpl}
 * 时传入并设置钩子，用来验证归档中途失败后的断点续跑。
 */
public final class ArchiveFailureHolder {

    /** 每批事务开始前调用，batchIndex 从 0 开始；抛异常即模拟该批失败（事务回滚）。 */
    @FunctionalInterface
    public interface Hook {
        void beforeBatch(int batchIndex);
    }

    private final AtomicReference<Hook> hook = new AtomicReference<>(batchIndex -> { });

    public void setHook(Hook hook) {
        this.hook.set(hook == null ? batchIndex -> { } : hook);
    }

    public void reset() {
        this.hook.set(batchIndex -> { });
    }

    Hook hook() {
        return hook.get();
    }
}

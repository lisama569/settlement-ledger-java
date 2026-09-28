package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.service.dto.InstructionSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;

import java.time.Instant;

/**
 * 对账快照：按审计历史重建单条指令的完整业务视图，以及按范围批量导出。
 */
public interface SnapshotRebuildService {

    /** 重建单条指令的完整业务快照，结果与实时读取一致。 */
    InstructionSnapshot rebuild(Long instructionId);

    /**
     * 按指令 id 游标分批导出；可叠加受理时间窗口。
     * 历史链条问题以 DATA_PROBLEM 条目显式返回，不静默跳过。
     */
    SnapshotExportPage exportBatch(Long cursor, Instant fromTime, Instant toTime, int size);
}

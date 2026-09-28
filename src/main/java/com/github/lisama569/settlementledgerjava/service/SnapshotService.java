package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.service.dto.SettlementSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportPage;
import com.github.lisama569.settlementledgerjava.service.dto.SnapshotExportRequest;

/**
 * 对账快照：按指令重建完整业务视图，以及按范围批量导出。
 */
public interface SnapshotService {

    SettlementSnapshot rebuild(Long instructionId);

    SnapshotExportPage exportRange(SnapshotExportRequest request);
}

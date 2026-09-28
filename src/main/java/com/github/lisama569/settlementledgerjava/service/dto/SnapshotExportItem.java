package com.github.lisama569.settlementledgerjava.service.dto;

/**
 * 批量导出中的单条结果：要么是正常快照，要么是可识别的数据问题（不静默跳过）。
 */
public record SnapshotExportItem(Long instructionId,
                                 boolean ok,
                                 SettlementSnapshot snapshot,
                                 String errorCode,
                                 String errorMessage) {

    public static SnapshotExportItem of(SettlementSnapshot snapshot) {
        return new SnapshotExportItem(snapshot.instructionId(), true, snapshot, null, null);
    }

    public static SnapshotExportItem error(Long instructionId, String errorCode, String errorMessage) {
        return new SnapshotExportItem(instructionId, false, null, errorCode, errorMessage);
    }
}

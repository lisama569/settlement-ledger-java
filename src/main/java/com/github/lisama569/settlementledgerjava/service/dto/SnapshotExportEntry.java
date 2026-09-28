package com.github.lisama569.settlementledgerjava.service.dto;

/**
 * 批量对账导出中的单条结果。kind=SNAPSHOT 时 snapshot 有值；
 * kind=DATA_PROBLEM 时 errorCode/errorMessage 描述历史链条问题，
 * 让调用方能看出是数据问题，而不会被静默跳过或当成正常快照。
 */
public record SnapshotExportEntry(String kind,
                                  Long instructionId,
                                  InstructionSnapshot snapshot,
                                  String errorCode,
                                  String errorMessage) {

    public static SnapshotExportEntry ok(InstructionSnapshot snapshot) {
        return new SnapshotExportEntry("SNAPSHOT", snapshot.instructionId(), snapshot, null, null);
    }

    public static SnapshotExportEntry problem(Long instructionId, String errorCode, String errorMessage) {
        return new SnapshotExportEntry("DATA_PROBLEM", instructionId, null, errorCode, errorMessage);
    }

    public boolean isProblem() {
        return "DATA_PROBLEM".equals(kind);
    }
}

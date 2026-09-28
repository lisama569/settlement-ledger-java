package com.github.lisama569.settlementledgerjava.service.dto;

import java.time.Instant;

/**
 * 按范围批量重建对账快照的请求。
 * 支持游标（afterInstructionId）或受理时间窗口（from/to）分批拉取。
 */
public record SnapshotExportRequest(Instant from,
                                    Instant to,
                                    Long afterInstructionId,
                                    int size) {
}

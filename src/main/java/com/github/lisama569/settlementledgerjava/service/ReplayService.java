package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

/**
 * 基于审计记录的重放：从审计流重建指令状态，须与实时处理结果一致。
 */
public interface ReplayService {

    SettlementStatus replayStatus(Long instructionId);
}

package com.github.lisama569.settlementledgerjava.audit;

import java.util.List;

/**
 * 审计历史读取的统一入口：合并热表与归档表，按 (instructionId, seq) 有序返回。
 * 归档与否对重建逻辑透明。
 */
public interface AuditHistoryReader {

    List<AuditHistoryEntry> historyOf(Long instructionId);

    /** 该指令当前最大的 seq（用于推进时生成下一条审计序号，归档后也不回退）。 */
    long maxSeqOf(Long instructionId);
}

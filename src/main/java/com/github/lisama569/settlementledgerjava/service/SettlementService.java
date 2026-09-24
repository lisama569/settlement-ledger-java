package com.github.lisama569.settlementledgerjava.service;

import com.github.lisama569.settlementledgerjava.service.dto.AdvanceResult;
import com.github.lisama569.settlementledgerjava.service.dto.AuditPage;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;

/**
 * 结算指令核心服务：受理（幂等）、状态推进（乐观并发）、游标分页。
 */
public interface SettlementService {

    SubmitResult submit(SubmitCommand command);

    AdvanceResult advance(Long instructionId, long expectedVersion, SettlementStatus target);

    AuditPage auditPage(Long cursor, int size);
}

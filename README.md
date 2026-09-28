# settlement-ledger-java

本地可验证的资金结算指令处理服务：Spring Boot 3 + 内嵌 H2 数据库，承载结算状态与审计记录，
保证并发提交、重复提交、历史结构变更、对账快照重建与审计归档下的结果一致且可追溯，
并能在进程重启后继续工作。

## 领域模型

- `SettlementInstruction`：结算指令。状态机 `RECEIVED -> VALIDATED -> SETTLED / FAILED`
  （`RECEIVED -> FAILED` 亦允许；终态不可再迁移）。`version` 字段（JPA `@Version`）承载乐观并发控制。
- `AuditRecord`：在线审计记录。每条含 `instructionId`、指令内单调递增的 `seq`、
  `fromStatus`/`toStatus`、`eventType`、`schemaVersion` 与 JSON `payload`。
- `ArchivedAuditRecord`：已归档审计记录，字段与在线表一致，**主键保留原始审计记录 id**。
- `AuditEventView`：在线/归档记录的统一只读视图；`AuditHistoryReader` 合并两张表，
  对上层提供与存放位置无关的完整审计链条。

## 并发控制

- 状态推进 `advance(instructionId, expectedVersion, target)` 采用乐观并发：
  事务内先显式比对 `expectedVersion`，不符即抛 `OptimisticConflictException`；
  即使两个事务同时通过校验，JPA `@Version` 在 flush 时也会让后提交者失败，
  统一映射为 `OptimisticConflictException`。
- 失败原因可区分：并发冲突 → `OptimisticConflictException`（HTTP 409 `OPTIMISTIC_CONFLICT`）；
  非法状态迁移 → `IllegalTransitionException`（HTTP 422 `ILLEGAL_TRANSITION`）。
- 原子性：指令状态变更与对应审计记录在**同一事务**内落库，任一步失败整体回滚，
  不会留下半条数据。
- 归档后继续推进时，新审计事件的 `seq` 由合并在线表+归档表后的最大 `seq` 推导
  （`AuditHistoryReader.nextSeq`），不会因历史已搬走而从 1 重新计数、造成链条断裂。

## 幂等规则

- `idempotency_key` 上有数据库唯一约束，是幂等的最终防线。
- `submit` 先尝试在事务内插入「指令 + 首条审计记录」；命中唯一约束时回读首次提交的行，
  返回相同的结果快照并置 `duplicate=true`，不追加任何审计记录。
- 同一幂等键并发提交时，唯一约束保证最终只有一次生效；其余调用方收到约束冲突后
  回读到与首次一致的结果（相同的 `instructionId` / `status` / `version`）。

## 重放与分页

- 重放：`ReplayService.replayStatus(instructionId)` 按 `seq` 顺序重放**在线+归档合并后**
  的完整审计链条，从初始状态逐步推进，并校验链条连续性（每条 `fromStatus` 必须等于上一条
  `toStatus`）。重放结果与实时状态一致（见 `ReplayConsistencyTest`）。
- 在线审计分页：`auditPage(cursor, size)` 使用键集（keyset）分页，按全局自增 `id` 排序，
  `cursor` 为上一页最后一条的 `id`。新写入的 `id` 总是更大，持续写入时不漏项、不重项
  （见 `AuditPaginationTest`）；`nextCursor == null` 表示没有更多数据。

## 对账快照重建（新增）

`SnapshotRebuildService` 基于审计历史重建指令的**完整业务视图**：

- `rebuild(instructionId)`：返回 `InstructionSnapshot`，含受理时记录的全部业务属性
  （金额、币种、收付款方、渠道 `channel`、以及结构演进新增的其它属性 `attributes`），
  加上当前状态 `status`、版本号 `version`、最后一次变更时间 `lastChangedAt`。
  - 业务属性取自 `SUBMITTED` 事件 payload（经结构演进补齐）；状态/版本/时间沿链条推进得出
    （受理为 version=0，每次状态变更 +1）。
  - 重建后与实时实体（当前状态、版本、更新时间）严格比对，任何不一致都按数据问题报出，
    因此**重建结果与实时读到的一致**。
  - 同一份数据无论重建多少次、是否已归档，结果完全一致。
- `exportBatch(cursor, fromTime, toTime, size)`：按指令 `id` 键集游标分批拉取并重建，
  可叠加受理时间窗口 `[from, to)`。一个范围内新旧结构混存时输出稳定，同一段范围重复导出
  （甚至用不同页大小）内容完全一致。
  - 边界：空范围返回空条目且 `nextCursor=null`；支持刚好一页与跨页；按严格 `id` 顺序，
    不漏不重。
  - 历史链条问题**显式暴露**：链条不连续 → 条目 `kind=DATA_PROBLEM`、
    `errorCode=AUDIT_CHAIN_BROKEN`；payload 无法解析 → `AUDIT_PAYLOAD_UNPARSEABLE`。
    问题条目仍占据范围中的一个位置，**不静默跳过，也不伪装成正常快照**。

## 结构演进兼容范围（新增）

审计 `payload` 带 `schemaVersion`，读取历史时由 `AuditPayloadUpcaster` 逐级、**确定性补齐**
（只在读取路径发生，不回写历史原文；当前写入版本为 3）：

| 版本迁移 | 固定补齐规则 |
| --- | --- |
| v1 → v2 | 缺 `channel` 补 `"UNKNOWN"`；老批次缺 `payer`/`payee`/`currency` 补 `"UNKNOWN"`；缺 `amount` 补 `"0"`（数值缺失的固定占位，对账方可显式识别，不会对错位置） |
| v2 → v3 | 缺 `businessType` 补 `"PAYMENT"` |

- 已有字段原样保留，绝不按位置猜列或丢字段；非对象 JSON / 关键业务字段类型错误等
  无法靠补齐规则修复的，报 `AUDIT_PAYLOAD_UNPARSEABLE`。
- 未提升为快照一等字段的标量属性，按 key 排序放入 `attributes`，保证导出确定性。

## 审计归档（新增）

`ArchiveService` 把较早的在线审计记录搬到归档表，且**归档不改变对账结果**：

- `archiveBefore(cutoff, batchSize)`：归档 `created_at < cutoff` 的记录；先确定候选集合的
  id 上界，再按该固定边界分批。归档进行期间新受理/推进的记录 id 更大、时间更新，绝不混入本批。
- `archiveUpToId(maxIdInclusive, batchSize)`：归档 id 不超过明确边界的记录，便于对账与验证。
- 每一批在独立事务内「按原 id 复制到归档表 + 从在线表删除」，原子提交：
  - 归档表主键保留原始 id，天然幂等：重试/重复执行不会产生重复行；已归档记录被跳过。
  - 批失败则整批回滚，在线表不丢记录；重试只搬迁剩余记录，**整体只生效一次**。
  - 在线表与归档表 id 不相交；重建/重放合并两表，因此归档前后快照完全一致，归档后仍可推进。

## 持久化与重启（新增）

- 默认使用**文件型 H2**（`jdbc:h2:file:./data/settlement`，`ddl-auto=update`）：进程重启后，
  已处理的指令、在线与归档审计历史都还在，可继续推进与重建（见 `RestartPersistenceTest`）。
- 测试使用内存库（`src/test/resources/application.properties`），互不依赖落盘文件。
- 本地运行产生的 `data/` 目录已加入 `.gitignore`。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/settlements` | 受理指令（幂等），返回 `SubmitResult` |
| POST | `/api/settlements/{id}/advance?expectedVersion=&target=` | 乐观并发推进状态 |
| GET | `/api/settlements/{id}/replay` | 基于审计记录重放状态 |
| GET | `/api/settlements/{id}/snapshot` | 按指令重建完整业务快照（新增） |
| GET | `/api/settlements/reconciliation/export?cursor=&from=&to=&size=` | 按范围批量导出对账快照（新增） |
| POST | `/api/settlements/audit/archive?before=&batchSize=` | 按时间归档较早审计记录（新增） |
| POST | `/api/settlements/audit/archive/up-to-id?maxIdInclusive=&batchSize=` | 按 id 边界归档（新增） |
| GET | `/api/settlements/audit?cursor=&size=` | 在线审计记录游标分页 |

数据问题在单条重建时返回 HTTP 422（`AUDIT_CHAIN_BROKEN` / `AUDIT_PAYLOAD_UNPARSEABLE`）；
批量导出时不整体失败，而是在结果条目中以 `kind=DATA_PROBLEM` 逐条显式返回。

## 本地验证

```bash
# 运行全部测试（23 个）：
#   并发冲突 / 幂等 / 重放一致性 / 在线分页边界 / 结构演进
#   完整重建 / 老结构确定性补齐 / 批量导出一致性与边界 /
#   归档前后一致 / 归档与写入并发不漏不重 / 归档失败重试幂等 / 重启持久化
mvn test

# 本地启动（默认文件型 H2，数据在 ./data，重启不丢）
mvn spring-boot:run

# 1) 受理 -> 推进 -> 重放 -> 重建快照
curl -s -X POST localhost:8080/api/settlements \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"demo-1","amount":100.00,"currency":"CNY","payer":"alice","payee":"bob"}'
curl -s -X POST 'localhost:8080/api/settlements/1/advance?expectedVersion=0&target=VALIDATED'
curl -s localhost:8080/api/settlements/1/replay
curl -s localhost:8080/api/settlements/1/snapshot

# 2) 按范围批量导出对账快照（游标 + 时间窗口，时间为 ISO-8601）
curl -s 'localhost:8080/api/settlements/reconciliation/export?size=10'
curl -s 'localhost:8080/api/settlements/reconciliation/export?from=2026-01-01T00:00:00Z&to=2027-01-01T00:00:00Z&size=10'

# 3) 归档较早历史（按时间 / 按 id 边界），归档后再次重建快照应完全一致
curl -s -X POST 'localhost:8080/api/settlements/audit/archive?before=2026-01-01T00:00:00Z&batchSize=500'
curl -s -X POST 'localhost:8080/api/settlements/audit/archive/up-to-id?maxIdInclusive=10&batchSize=5'
curl -s localhost:8080/api/settlements/1/snapshot

# 4) 重启验证：Ctrl-C 停止后重新 mvn spring-boot:run，数据仍在 ./data，
#    再次 GET /snapshot 结果不变，并可继续 /advance。
```

测试与运行日志持续打印幂等键、版本号与关键状态快照
（`idempotencyKey=... status=... version=... lastChangedAt=...`），
便于核对并发、幂等、重建与归档行为。

# settlement-ledger-java

本地可验证的资金结算指令处理服务：Spring Boot 3 + 内嵌 H2 数据库，承载结算状态与审计记录，
保证并发提交、重复提交与历史结构变更下的结果一致且可追溯。

## 领域模型

- `SettlementInstruction`：结算指令。状态机 `RECEIVED -> VALIDATED -> SETTLED / FAILED`
  （`RECEIVED -> FAILED` 亦允许；终态不可再迁移）。`version` 字段（JPA `@Version`）承载乐观并发控制。
- `AuditRecord`：审计记录。每条记录含 `instructionId`、指令内单调递增的 `seq`、
  `fromStatus`/`toStatus`、`eventType`、`schemaVersion` 与 JSON `payload`。

## 并发控制

- 状态推进 `advance(instructionId, expectedVersion, target)` 采用乐观并发：
  事务内先显式比对 `expectedVersion`，不符即抛 `OptimisticConflictException`；
  即使两个事务同时通过校验，JPA `@Version` 在 flush 时也会让后提交者失败，
  统一映射为 `OptimisticConflictException`。
- 失败原因可区分：并发冲突 → `OptimisticConflictException`（HTTP 409 `OPTIMISTIC_CONFLICT`）；
  非法状态迁移 → `IllegalTransitionException`（HTTP 422 `ILLEGAL_TRANSITION`）。
- 原子性：指令状态变更与对应审计记录在**同一事务**内落库，任一步失败整体回滚，
  不会留下半条数据。

## 幂等规则

- `idempotency_key` 上有数据库唯一约束，是幂等的最终防线。
- `submit` 先尝试在事务内插入「指令 + 首条审计记录」；命中唯一约束时回读首次提交的行，
  返回相同的结果快照并置 `duplicate=true`，不追加任何审计记录。
- 同一幂等键并发提交时，唯一约束保证最终只有一次生效；其余调用方阻塞后收到约束冲突，
  回读到与首次一致的结果（相同的 `instructionId` / `status` / `version`）。

## 重放与分页

- 重放：`ReplayService.replayStatus(instructionId)` 按 `seq` 顺序重放审计记录，
  从初始状态逐步推进，并校验链条连续性（每条 `fromStatus` 必须等于上一条 `toStatus`）。
  重放结果与实时状态一致（见 `ReplayConsistencyTest`）。
- 分页：`auditPage(cursor, size)` 使用键集（keyset）分页，按全局自增 `id` 排序，
  `cursor` 为上一页最后一条的 `id`。新写入的 `id` 总是更大，因此数据持续写入时
  不漏项、不重项（见 `AuditPaginationTest`）；`nextCursor == null` 表示没有更多数据。

## 结构演进语义

- 审计 `payload` 带 `schemaVersion`（当前版本 2）。
- 读取历史记录时由 `AuditPayloadUpcaster` 按版本逐级**确定性补齐**：
  v1 → v2 补充缺失的 `channel` 字段（默认 `"UNKNOWN"`），已有字段原样保留。
  不会静默错列或丢字段；同一输入重复补齐结果完全一致。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/settlements` | 受理指令（幂等），返回 `SubmitResult` |
| POST | `/api/settlements/{id}/advance?expectedVersion=&target=` | 乐观并发推进状态 |
| GET | `/api/settlements/{id}/replay` | 基于审计记录重放状态 |
| GET | `/api/settlements/audit?cursor=&size=` | 审计记录游标分页 |

## 本地验证

```bash
# 运行全部测试（并发冲突 / 幂等 / 重放一致性 / 分页边界 / 结构演进）
mvn test

# 本地启动（内嵌 H2，内存库）
mvn spring-boot:run

# 示例：受理 -> 推进 -> 重放 -> 分页
curl -s -X POST localhost:8080/api/settlements \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"demo-1","amount":100.00,"currency":"CNY","payer":"alice","payee":"bob"}'
curl -s -X POST 'localhost:8080/api/settlements/1/advance?expectedVersion=0&target=VALIDATED'
curl -s localhost:8080/api/settlements/1/replay
curl -s 'localhost:8080/api/settlements/audit?size=10'
```

测试日志会打印幂等键、版本号与关键状态快照（`idempotencyKey=... status=... version=...`），
便于核对并发与幂等行为。

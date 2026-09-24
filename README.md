# Settlement Ledger（资金结算指令处理）

一套本地可验证的结算指令处理能力：以**内嵌 H2 数据库**承载指令当前态、不可变审计事件与幂等记录，
支持并发提交/推进下的结果一致性、幂等受理、审计重放、游标分页与确定性的结构演进。

- Java 21 / Spring Boot 3.4 / Spring JDBC / H2（无 ORM，SQL 与并发语义显式可控）
- 无外部中间件依赖，`mvn test` 即可在内存库上完成全部并发/幂等/重放验证
- 文件库模式启动后数据落盘，可直接 curl 端到端验证

## 1. 领域模型

指令状态机：

```
ACCEPTED ──► PROCESSING ──► SETTLED
    │             │
    └──► REJECTED ◄┘
```

`SETTLED` / `REJECTED` 为终态。每次受理或状态推进都产生一条不可变审计事件。

| 表 | 作用 |
| --- | --- |
| `settlement_instructions` | 指令当前态（物化视图），含 `version` 乐观版本列 |
| `settlement_events` | 审计事件流，`seq_no` 全局单调（identity），行内带 `payload_version` |
| `idempotency_records` | 幂等键 → 指令、请求指纹、缓存最新状态（主键唯一） |
| `schema_version` | 结构迁移版本登记 |

## 2. 并发控制（乐观并发）

- 每条指令带单调递增的 `version`。推进接口必须携带调用方读到的 `expectedVersion`。
- 推进在**单条 UPDATE** 内做比较与设置（CAS）：

  ```sql
  UPDATE settlement_instructions
     SET status = ?, version = ?, rejection_reason = ?, updated_at = ?
   WHERE instruction_id = ? AND version = ?   -- 期望版本
  ```

  返回 0 行即基准版本已过期，服务抛出 `OptimisticLockConflictException`
  （错误码 `OPTIMISTIC_LOCK_CONFLICT`，HTTP 409），携带 `expectedVersion` 与 `actualVersion`，
  与"非法状态流转"（`ILLEGAL_STATUS_TRANSITION`，HTTP 400）是**不同错误码**，调用方可以区分。
- 状态更新、审计事件追加、幂等记录联动在**同一事务**内提交；任一步失败整体回滚，不留半条数据。
- 并发正确性由数据库行锁 + WHERE 版本条件保证：同一基准版本的多个并发推进恰好一个成功，其余全部冲突。
  见 `SettlementConcurrencyTest`（8 线程并发推进，断言恰 1 成功、7 个可区分冲突）。

## 3. 幂等规则

- 受理请求**必须**携带 `idempotencyKey`。服务对请求业务内容计算 SHA-256 **请求指纹**
  （指令号、借贷账户、金额（归一化到 2 位小数）、币种、渠道、提交人）。
- 同一幂等键：
  - **首次**：插入幂等记录（唯一主键竞争）+ 指令行 + `SUBMITTED` 事件，同一事务原子落库，
    返回 `duplicated=false`（HTTP 201）。
  - **重复提交且指纹一致**：不重复生效，返回与首次一致的指令号/状态/版本，`duplicated=true`
    （HTTP 200）——结果一致且与首次可区分。重复发生在生命周期中段时，返回的是该键对应指令的**最新**状态。
  - **同键但指纹不同**：判定为键被复用的冲突请求，抛 `IDEMPOTENCY_KEY_DUPLICATED_CONFLICT`
    （HTTP 409），不生效。
  - **同一键并发提交**：由 `idempotency_records` 主键唯一约束裁决，`insertIfAbsent` 捕获
    `DuplicateKeyException`，败者回退为"重复命中"语义，最终**恰好一次**生效
    （`SettlementConcurrencyTest` 8 线程并发提交，断言恰 1 次首次、7 次重复命中）。

## 4. 审计重放

- 事件按 `seq_no` 升序折叠：`SUBMITTED` 产生 `version=1` 的 `ACCEPTED` 快照，
  每条 `STATUS_CHANGED` 使版本 +1，与实时库版本语义严格一致。
- `GET /api/settlement/instructions/{id}/replay` 返回重放快照；
  `ReplayAndPaginationTest.replay_matches_live_state...` 断言完整生命周期后**重放结果与实时态逐字段相等**。
- 事件是唯一事实来源；指令表只是可由事件流重建的物化状态。

## 5. 游标分页（持续写入下不漏不重）

- 采用**键集分页（keyset pagination）**，不用 OFFSET：

  ```sql
  SELECT ... FROM settlement_events WHERE seq_no > ? ORDER BY seq_no ASC FETCH FIRST n ROWS ONLY
  ```

- 游标是"已消费的最后一条 `seq_no`"。取 `limit+1` 条判断是否有下一页，`nextCursor` 为本页末条 `seq_no`。
- 数据持续写入时，新事件的 `seq_no` 严格更大，不会插入到旧游标之前，因此**不会漏项也不会重项**；
  这也避免了 OFFSET 分页在写入时的错位问题。
- `ReplayAndPaginationTest.cursor_pagination_...` 在翻页过程中持续注入新写入，
  断言抓取序列无重复、严格升序、不含测试前旧数据。

## 6. 结构演进语义（确定性补齐）

- 结构由 `SchemaInitializer` 自举：维护 `schema_version` 表，按版本号顺序执行
  `src/main/resources/db/migration/V*.sql`；每个迁移脚本与其版本登记在**同一事务**提交。
  新库一次到位，老库只增量执行，重启不重复执行（已端到端验证）。
- 已包含一次真实演示范例：
  - **V1**：基础三表。
  - **V2**：`settlement_instructions` 新增 `channel` 列，历史行以确定性默认值 `'UNKNOWN'`
    回填（DDL `DEFAULT 'UNKNOWN'`），而不是留 NULL 制造歧义。
- **事件负载版本化**：`settlement_events.payload_version` 行内标记负载结构。
  - v1 的 `SUBMITTED` 负载没有 `channel` 字段；
  - v2 起包含 `channel`；
  - 重放解码时 `EventPayloads.decode(version, json)` 按行内版本解析并做**确定性归一化**：
    缺失的 `channel` 一律补为 `UNKNOWN`，未知字段忽略（`FAIL_ON_UNKNOWN_PROPERTIES=false`）。

  因此历史数据按确定语义补齐，**不会静默错列或丢字段**；历史行的原始 `payload_version` 原样保留，可审计。
  见 `ReplayAndPaginationTest.legacy_v1_payload_is_deterministically_backfilled_on_replay`。

## 7. 失败原因区分（错误码）

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 参数非法（金额非正、缺必填、拒付缺 reason 等） |
| `ILLEGAL_STATUS_TRANSITION` | 400 | 状态机不允许的流转 / 终态再推进 |
| `OPTIMISTIC_LOCK_CONFLICT` | 409 | 乐观版本冲突（含 expected/actual 版本） |
| `IDEMPOTENCY_KEY_DUPLICATED_CONFLICT` | 409 | 幂等键被不同请求指纹复用 |
| `INSTRUCTION_NOT_FOUND` | 404 | 指令不存在 |

正常的重复提交不是错误：HTTP 200 + `duplicated=true`，与首次 201 + `duplicated=false` 可区分。

## 8. API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/settlement/instructions` | 受理（body 需 `idempotencyKey`） |
| POST | `/api/settlement/instructions/{id}/transitions` | 状态推进（`expectedVersion`、`targetStatus`、`reason`） |
| GET | `/api/settlement/instructions/{id}` | 实时当前态 |
| GET | `/api/settlement/instructions/{id}/replay` | 审计重放态 |
| GET | `/api/settlement/events?cursor=&limit=` | 事件游标分页 |

## 9. 本地验证方法

### 9.1 自动化测试（推荐，内存 H2）

```bash
mvn test
```

16 个测试覆盖：并发乐观冲突、同键并发提交、重复提交/指纹冲突、完整生命周期、
事务回滚不留半条数据、重放与实时态一致、持续写入下分页不漏不重、v1 历史负载确定性补齐、
REST 状态码与错误码区分。

测试日志会打印**幂等键、版本号与关键状态快照**，例如：

```
submit accepted idempotencyKey=... instructionId=... version=1 status=ACCEPTED snapshot=InstructionSnapshot(...)
TRANSITION-LOST instructionId=... expectedVersion=1 actualVersion=2 errorCode=OPTIMISTIC_LOCK_CONFLICT
submit idempotent hit idempotencyKey=... instructionId=... version=3 status=SETTLED snapshot=...
replay-compare idempotencyKey=... liveVersion=3 replayVersion=3 liveStatus=SETTLED replayStatus=SETTLED
pagination totalFetched=7 distinct=7 ...
legacy-replay ... channel(backfilled)=UNKNOWN
```

可按类单独运行：

```bash
mvn test -Dtest=SettlementConcurrencyTest        # 并发：推进冲突 + 同键并发
mvn test -Dtest=IdempotencyAndTransitionTest      # 幂等 + 状态机 + 错误码区分
mvn test -Dtest=ReplayAndPaginationTest           # 重放一致性 + 分页边界 + 历史补齐
mvn test -Dtest=AtomicityTest                     # 失败回滚不留半条数据
```

### 9.2 端到端 curl（文件 H2，数据落盘）

```bash
mvn spring-boot:run          # 默认 8080；数据文件在 ./data/，H2 控制台 /h2-console

# 受理（首次 201 duplicated=false）
curl -i -X POST localhost:8080/api/settlement/instructions \
  -H 'Content-Type: application/json' -d '{
    "idempotencyKey":"key-1","instructionId":"INS-1",
    "debtorAccount":"DEBT-1","creditorAccount":"CRED-1",
    "amount":250.75,"currency":"CNY","channel":"MOBILE","submittedBy":"alice"}'

# 重复提交（200 duplicated=true，结果一致）；推进；重放；分页
curl -X POST localhost:8080/api/settlement/instructions/INS-1/transitions \
  -H 'Content-Type: application/json' -d '{"expectedVersion":1,"targetStatus":"PROCESSING"}'
curl localhost:8080/api/settlement/instructions/INS-1/replay
curl "localhost:8080/api/settlement/events?limit=50"
```

重启应用后 `schema_version` 保证迁移不重复执行，`./data/` 中历史数据保留，可继续重放/翻页。

## 10. 关键代码位置

| 关注点 | 位置 |
| --- | --- |
| 版本化结构迁移 | `migration/SchemaInitializer.java`、`resources/db/migration/V*.sql` |
| 乐观 CAS 更新 | `repository/InstructionRepository.java#compareAndUpdateStatus` |
| 幂等唯一竞争/指纹 | `repository/IdempotencyRepository.java`、`service/SettlementService.java#submit` |
| 原子事务边界 | `service/SettlementService.java`（`@Transactional`） |
| 事件追加/键集分页 | `repository/EventRepository.java` |
| 重放折叠/历史归一化 | `service/ReplayService.java`、`service/EventPayloads.java` |

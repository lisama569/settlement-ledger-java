# settlement-ledger-java

本地可验证的资金结算指令处理服务：Spring Boot 3 + 内嵌 H2 数据库（默认为文件型、提交即落盘），
承载结算状态与审计记录，保证并发提交、重复提交、历史结构变更、对账快照重建/导出、历史归档以及
**进程重启（含被直接杀进程）**下的结果一致、可追溯且不丢数据。

## 领域模型

- `SettlementInstruction`：结算指令。状态机 `RECEIVED -> VALIDATED -> SETTLED / FAILED`
  （`RECEIVED -> FAILED` 亦允许；终态不可再迁移）。`version` 字段（JPA `@Version`）承载乐观并发控制。
- `AuditRecord`：热表审计记录。每条记录含 `instructionId`、指令内单调递增的 `seq`、
  `fromStatus`/`toStatus`、`eventType`、`schemaVersion` 与 JSON `payload`。
- `ArchivedAuditRecord`：归档表，字段与热表一一对应，另含 `sourceId`（热表原 id）、`runId`（归档批次）。
- `ArchiveRun`：归档批次状态（断点游标 `lastSourceId`、`archivedCount`、`completed`）。

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

- 重放：`ReplayService.replayStatus(instructionId)` 按 `seq` 顺序重放审计记录（**热表 + 归档表合并视图**），
  从初始状态逐步推进，并校验链条连续性（每条 `fromStatus` 必须等于上一条 `toStatus`）。
  重放结果与实时状态一致（见 `ReplayConsistencyTest`），归档前后也一致。
- 分页：`auditPage(cursor, size)` 使用键集（keyset）分页，按全局自增 `id` 排序，
  `cursor` 为上一页最后一条的 `id`。新写入的 `id` 总是更大，因此数据持续写入时
  不漏项、不重项（见 `AuditPaginationTest`）；`nextCursor == null` 表示没有更多数据。

## 对账快照重建

- 单指令：`GET /api/settlements/{id}/snapshot`（`SnapshotService.rebuild`）一次给出完整业务视图：
  金额、币种、收付款双方，以及受理事件 payload 里记录的其它业务属性（`attributes`，按 key 排序），
  再加上当前状态、版本号、受理时间 `receivedAt` 与最后变更时间 `lastChangedAt`。
- 权威来源分工：**状态/版本/时间戳/金额/币种/收付方取自实时行**（保证与实时读一致），
  **其它业务属性取自受理事件 payload**（经结构补齐）。收付方等核心字段不会因老 payload 缺字段而错位或丢失。
- 重建前会校验完整审计链：`seq` 必须从 1 连续、`fromStatus` 必须链接上一条 `toStatus`、
  重放终态必须等于实时状态。任一不符抛 `SnapshotDataIntegrityException`（HTTP 422，
  错误码 `CHAIN_BROKEN` / `NO_SUBMIT_EVENT` / `PAYLOAD_UNPARSEABLE` / `INSTRUCTION_NOT_FOUND`），
  不返回残缺快照。

## 按范围批量导出

- `GET /api/settlements/snapshots?from=&to=&afterId=&size=`（`SnapshotService.exportRange`）：
  - 支持按受理时间窗口（`from` 含、`to` 不含）和/或键集游标（`afterId`）分批拉取；
  - 按指令 `id` 严格升序，满页返回 `nextCursor`，再翻一页为空且 `nextCursor=null`；
    空范围直接返回空列表 + `null` 游标；
  - 同一段范围重复导出逐字段完全一致（新旧结构混在一起也稳定）。
- 数据问题**不静默跳过、不当正常快照**：问题指令以 `{ok:false, errorCode, errorMessage}`
  形式作为条目返回，正常条目才带 `snapshot`。链条不连续 → `CHAIN_BROKEN`；
  payload 无法解析 → `PAYLOAD_UNPARSEABLE`。调用方据此区分健康数据与历史脏数据。

## 结构演进语义（兼容范围）

- 审计 `payload` 带 `schemaVersion`，当前版本 **2**。兼容读取范围：**v1 与 v2**。
- 读取历史记录时由 `AuditPayloadUpcaster` 按版本逐级**确定性补齐**：
  v1 → v2 补充缺失的 `channel` 字段（默认 `"UNKNOWN"`），已有字段原样保留。
  不会静默错列或丢字段；同一输入（无论重复多少次、与新结构是否混用）补齐结果完全一致。
- 未来再加 v3 时，只在 upcaster 中追加 `v2 -> v3` 一级规则即可，老数据读取路径不变。
- 结构变更前落库、字段缺失的批次：重建快照时实时行提供权威核心字段，payload 仅做补齐，
  因此不会因字段缺失报错、丢字段或对错位置。

## 历史归档（不影响对账）

- `POST /api/settlements/archives?runId=&cutoff=`（`ArchiveService.archive`）把
  `createdAt < cutoff` 的较早审计记录从热表搬到归档表：
  - **只搬旧记录**：cutoff 之后受理/推进产生的新记录永远不被搬动，归档进行期间写入照常完成；
  - **每批独立事务**：「插入归档表 + 删除热表」同事务，任一步失败整批回滚，不漏不重；
  - **断点续跑**：以 `ArchiveRun.lastSourceId` 为游标，归档中途失败后用**同一个 runId** 重试，
    已提交批次不重复、未处理批次继续搬；
  - **只生效一次**：`runId` 有唯一约束，已完成批次重放原结果（`alreadyApplied=true`），不再搬动；
  - 重建/重放统一走 `MergedAuditHistoryReader`（热表 + 归档表按 `seq` 合并），
    因此**归档前后重建快照完全一致**，归档后仍可继续推进（下一条 `seq` 基于合并历史计算，不回退）。

## 进程重启持久性

- **默认就是持久化的**：`application.properties` 使用文件型 H2
  （`jdbc:h2:file:./data/settlement;AUTO_SERVER=TRUE;WRITE_DELAY=0`）+ `ddl-auto=update`。
  无论用哪种方式启动——直接 `mvn spring-boot:run`、`java -jar`，还是 IDE 运行 `Application`——
  只要受理/推进/归档的接口已经成功返回，数据就已落盘。进程停掉（包括 `kill -9` / 直接杀进程、
  机器断电这类**非优雅退出**）再起来，指令、完整审计历史、归档批次与归档表数据都还在：
  - 按原标识查到的是同一条指令，状态/版本/时间戳/金额/收付方逐字段不变；
  - 用同一幂等键重复受理，仍返回首次那条（`duplicate=true`），不会变成新指令；
  - 可接着往下推进，新审计记录的 `seq` 接着合并历史（含已归档部分）继续递增，不回退、不撞号；
  - 已归档历史重启后照常生效：合并重建/重放结果不变，同一 `runId` 不会再搬一遍
    （`alreadyApplied=true`），热表与归档表不重不漏；
  - 对同一段范围反复重建、批量导出，结果稳定一致、可复现。
- **为什么硬杀也不丢已确认的写入**：
  - 文件型 H2 的 MVStore 对每个已提交事务做日志；URL 上的 `WRITE_DELAY=0` 关闭“攒一批再刷盘”，
    提交即 `fsync`，因此接口返回成功后即使 JVM 被 SIGKILL，数据也已在磁盘上；
  - `ddl-auto=update` 只对已有库做增量结构更新，**绝不 drop**，重启不会清表；
  - 受理/推进的“指令行 + 审计行”、归档的“插入归档表 + 删除热表行 + 推进批次游标”
    都在同一事务内提交，原子可见，重启不会读到半条。
- 数据文件位于启动工作目录下的 `./data/settlement.mv.db`（已在 `.gitignore` 忽略）。
  `AUTO_SERVER=TRUE` 允许同机多个进程以服务器模式协商访问同一文件；正常单实例重启会直接接管。
  需要一份全新的空库时，停服后删除 `./data` 目录再启动即可。
- `local` profile（`application-local.properties`）与默认行为等价，保留给希望显式声明持久化的场景：
  ```bash
  mvn spring-boot:run -Dspring-boot.run.profiles=local
  ```
- 自动化测试如何模拟重启：
  - `RestartPersistenceTest` 用文件型 H2 + 两个独立 Spring 上下文模拟“进程退出 → 重启”，
    断言重启后快照逐字段不变、可继续推进、幂等键仍生效、已归档历史仍参与重建、
    批量导出跨重启一致且可重复、重启后继续推进的 `seq` 正确衔接；
  - `HardKillRestartTest` 更进一步：fork 两个**独立 JVM** 跑真实 Web 服务，第一段通过 HTTP
    受理/推进/归档并确认成功后用 `Process.destroyForcibly()`（POSIX 下即 SIGKILL，退出码 137）
    直接杀掉，不走任何优雅关闭；第二个全新 JVM 起同一份库文件，验证快照与导出逐字段一致、
    幂等受理、归档不重复、可继续推进。
- 仅测试运行时使用**内存库**（`src/test/resources/application.properties`），保证用例彼此隔离、
  可重复执行；上述两个持久性用例通过命令行参数显式切到临时目录下的文件型 H2，不受该默认值影响。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/settlements` | 受理指令（幂等），返回 `SubmitResult` |
| POST | `/api/settlements/{id}/advance?expectedVersion=&target=` | 乐观并发推进状态 |
| GET | `/api/settlements/{id}/replay` | 基于审计记录重放状态 |
| GET | `/api/settlements/{id}/snapshot` | 重建该指令的完整业务快照 |
| GET | `/api/settlements/snapshots?from=&to=&afterId=&size=` | 按范围批量导出对账快照 |
| POST | `/api/settlements/archives?runId=&cutoff=` | 归档较早审计历史（runId 幂等） |
| GET | `/api/settlements/audit?cursor=&size=` | 审计记录游标分页 |

## 本地验证

```bash
# 运行全部测试（24 个）：并发 / 幂等 / 重放 / 分页 / 结构演进 /
# 快照重建 / 批量导出 / 归档一致性 / 归档并发 / 归档断点续跑 /
# 重启持久性（上下文级 3 个用例 + 独立 JVM 硬杀）
mvn test

# 只跑重启持久性相关用例
mvn -Dtest='RestartPersistenceTest,HardKillRestartTest' test

# 本地启动：默认即为文件库，停掉再起来数据仍在（无需任何额外 profile）
mvn spring-boot:run
# 或打 jar 后用平时的方式运行
mvn package -DskipTests
java -jar target/settlement-ledger-java-1.0-SNAPSHOT.jar
# 想显式声明持久化也可以用 local profile（行为与默认一致）
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 受理 -> 推进 -> 完整快照 -> 重放
curl -s -X POST localhost:8080/api/settlements \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"demo-1","amount":100.00,"currency":"CNY","payer":"alice","payee":"bob"}'
curl -s -X POST 'localhost:8080/api/settlements/1/advance?expectedVersion=0&target=VALIDATED'
curl -s localhost:8080/api/settlements/1/snapshot
curl -s localhost:8080/api/settlements/1/replay

# 按范围批量导出（游标/时间窗口），观察 nextCursor 逐页拉取
curl -s 'localhost:8080/api/settlements/snapshots?size=10'
curl -s 'localhost:8080/api/settlements/snapshots?from=2026-01-01T00:00:00Z&to=2027-01-01T00:00:00Z&afterId=1&size=10'

# 归档较早历史（同一 runId 重试只生效一次），归档后再次取快照对比
curl -s -X POST 'localhost:8080/api/settlements/archives?runId=batch-1&cutoff=2026-09-01T00:00:00Z'
curl -s localhost:8080/api/settlements/1/snapshot
```

### 手动复现“受理/推进/归档 -> 停进程 -> 重启 -> 继续查询/推进/重建/导出”

```bash
mvn package -DskipTests
# 1) 启动（默认文件库；如 8080 被占用可加 --server.port=18080）
java -jar target/settlement-ledger-java-1.0-SNAPSHOT.jar
# 2) 另开终端：受理、推进、归档，并各存一份快照/导出结果
curl -s -X POST localhost:8080/api/settlements -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"restart-demo","amount":100.00,"currency":"CNY","payer":"alice","payee":"bob"}'
curl -s -X POST 'localhost:8080/api/settlements/1/advance?expectedVersion=0&target=VALIDATED'
curl -s localhost:8080/api/settlements/1/snapshot | tee before.json
curl -s -X POST 'localhost:8080/api/settlements/archives?runId=batch-demo&cutoff=2030-01-01T00:00:00Z'
# 3) 直接杀进程（模拟非正常退出；找到 jar 对应的 pid）
jps -l | grep settlement-ledger-java | awk '{print $1}' | xargs -r kill -9
# 4) 用完全相同的方式重新启动
java -jar target/settlement-ledger-java-1.0-SNAPSHOT.jar
# 5) 验证：快照逐字段一致、重复受理幂等、归档不重复、可继续推进
diff <(cat before.json) <(curl -s localhost:8080/api/settlements/1/snapshot) && echo IDENTICAL
curl -s -X POST localhost:8080/api/settlements -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"restart-demo","amount":1.00,"currency":"X","payer":"x","payee":"y"}'   # duplicate=true, id 仍为 1
curl -s -X POST 'localhost:8080/api/settlements/archives?runId=batch-demo&cutoff=2030-01-01T00:00:00Z'  # alreadyApplied=true
curl -s -X POST 'localhost:8080/api/settlements/1/advance?expectedVersion=1&target=SETTLED'  # version=2，seq 接续
curl -s localhost:8080/api/settlements/1/replay
```

各场景与测试对应关系：

| 场景 | 测试类 |
| --- | --- |
| 新旧结构混合的完整重建、重建与实时读一致 | `SnapshotRebuildTest` |
| 老结构缺字段的确定性补齐、重复重建一致 | `SnapshotRebuildTest` |
| 批量导出空范围/刚好一页/跨页、重复导出一致 | `SnapshotExportTest` |
| 链条断裂 / payload 无法解析以错误项可见 | `SnapshotExportTest` |
| 归档前后快照与重放完全一致、归档后可继续推进 | `ArchiveConsistencyTest` |
| 归档与受理/推进并发：不漏不重不误归档 | `ArchiveConcurrencyTest` |
| 归档中途失败、同 runId 重试只生效一次 | `ArchiveRetryTest` |
| 进程重启后历史（含归档）仍可用、导出跨重启一致、可继续推进与重建 | `RestartPersistenceTest` |
| 归档进行期间并发的新受理/推进，重启后不丢不重、归档不重复 | `RestartPersistenceTest` |
| 独立 JVM 被 SIGKILL 后重启：快照/导出逐字段一致、幂等、归档不重复 | `HardKillRestartTest` |
| 既有并发/幂等/分页/重放不回归 | `ConcurrencyConflictTest` / `IdempotencyTest` / `AuditPaginationTest` / `ReplayConsistencyTest` |

测试日志会打印幂等键、版本号与关键状态快照（`idempotencyKey=... status=... version=...`，
以及 `snapshot rebuilt ... events=...`、`archive batch moved ... total=...`），
便于核对并发、幂等、重建与归档行为。

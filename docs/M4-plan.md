# M4 方案（草案，基准 0.2.30 之后实施）

> 本文只是方案，不含实现。M0–M3 已完成并定基准（见根目录 `BASELINE.md`）。

## 0. 现状与约束

- 现有 19 个工具：状态/只读检索（8）、M2 发送五件套（5）、M3 分析（4）、`wx_send_limits` / `wx_probe_send` / `wx_raw_sql`。
- 传输：模块在微信进程内起 HTTP 服务 `127.0.0.1:18765`，JSON-RPC over HTTP，**请求-响应**模型（单连接内并发、按序回写）。
- 关键约束：**HTTP 拉模式没有服务端主动推送通道**（除非加 SSE）。所以"AI 感知新消息"必须靠**客户端轮询**或**长轮询**实现，不能照搬 stdio/SSE 的 `notifications`。
- 所有写操作沿用既有纪律：默认 `dry_run=true`、发后回查消息库校验落库类型、失败返回结构化错误码。
- 数据库访问统一走 `DbGateway.query`（主线程、wechatai 反射链、白名单表）。`message.createTime` 是**毫秒**。

## 1. 候选方向对比

| 方向 | 价值 | 成本 | 风险 | 结论 |
|---|---|---|---|---|
| A 增量/监听（`wx_incremental` + `wx_watch` 长轮询） | 高：AI 能"盯着"微信，是新能力而不是补齐 | 中 | 低（只读 + 轮询） | **M4.1 做** |
| B 写操作扩展（备注名、撤回、群管理） | 中高 | 中高 | 中高（要反编译找 netScene / 直写 rcontact 有同步风险） | **M4.2 只做备注名**，群管理延后 |
| C 媒体（收图/收视频解密 `.dat`、URL 直发图） | 中 | 高（图片密钥与版本强相关） | 高 | 不做，除非明确需要 |
| D 工程质量（单测、CI 缓存、DB 超时重试） | 中（降低回归成本） | 低 | 低 | **M4.1 顺带做单测骨架** |
| E MCP 协议层（resources/prompts/SSE 推送） | 中 | 中 | 中（客户端兼容性未知） | 探明客户端支持后再做，M5 |

## 2. M4.1：增量拉取 + 长轮询

### 2.1 `wx_incremental`（只读）

参数：

| 名字 | 类型 | 说明 |
|---|---|---|
| `talker` | string? | 只取该会话；留空为全库 |
| `after_msg_id` | string? | 游标：只要 `msgId` 比它新的消息 |
| `after_ts` | int? | 兜底游标：毫秒时间戳；与 `after_msg_id` 二选一 |
| `limit` | int | 默认 50，上限 500 |
| `types` | string? | 逗号分隔消息类型过滤，如 `1,3,49` |
| `max_chars` | int | 单条 content 截断，默认 500 |

返回：`messages[]`（字段同 `wx_context` 的 `anRow`，含 `time`）、`cursor`（`{msgId, createTime}`）、`hasMore`、`count`。

实现要点：
- 排序键统一用 `(createTime, msgId)`，SQL 用复合比较，**不要**只比 `createTime`（同秒多条的坑已在 M3 踩过）。
- 游标由服务端回传，客户端下次原样带回，避免客户端拼时间戳。
- 无游标时返回"最近 limit 条"，同时给 `cursor`，让调用方第一次就能对齐水位线。

### 2.2 `wx_watch`（长轮询，只读）

参数：`cursor`（必填或由 `talker`+`after_msg_id` 组合）、`timeout_ms`（默认 20000，上限 60000）、`limit`。

行为：内部按 500–800ms 间隔轮询 DB，直到 `(createTime, msgId)` 超过游标或超时；返回与 `wx_incremental` 相同结构 + `timedOut: true/false`、`waitedMs`。

实现要点：
- **不占主线程**：`DbGateway.query` 现在强制主线程，长轮询必须在后台线程 sleep、每轮回主线程查库（用 `Handler.post` + `CountDownLatch`），不能把主线程挂 20 秒。
- 单实例限制：同一时刻只允许一个 `wx_watch` 在等，多余请求立刻返回 `WATCH_BUSY`，避免线程堆积（微信主线程不能被多路长轮询拖住）。
- 超时返回空数组 + `timedOut=true`，不是错误。

### 2.3 可选加速（探测式，不默认开）

hook 微信收消息入口（如 `q61` 系列或 `com.tencent.mm.model.*` 的新消息回调）拿"有新消息"信号，唤醒长轮询。**只有反射探测成功才启用**，类名对不上就纯轮询。理由：hook 点随微信版本漂移，不能作为唯一依赖。

### 2.4 单测骨架（D）

把纯函数抽到 `AnalysisPure.kt`（热词切分/降噪、CSV 转义、时间格式化、游标比较），加 JVM 单测：

- 表情占位符 `[捂脸]` 不入词、URL 不产生 `https`/`com`、`com` 在噪音表里被丢。
- CSV 字段含逗号/引号/换行时的转义。
- `anTime` 东八区边界（跨日、0 值）。
- 游标比较：同 `createTime` 不同 `msgId` 的先后。

CI 增加 `./gradlew :module:testDebugUnitTest` 步骤（当前只 `assembleRelease`）。

### 2.5 验收标准

1. `wx_incremental` 无游标 → 返回最近 N 条 + 可用 `cursor`；带游标 → 只返回更新的消息，重复调用不重不漏。
2. `wx_watch` 在有人发消息时 **< 1.5s** 返回该消息；无人发消息时按 `timeout_ms` 返回 `timedOut=true`。
3. 长轮询期间 `wx_status` 仍能 200 响应（证明没堵主线程/没被单连接串行化卡死）。
4. `./gradlew :module:testDebugUnitTest` 全绿，且 CI 里跑起来。

## 3. M4.2：备注名写 + 分页 + 健壮性

### 3.1 `wx_set_remark`（写，默认 dry_run）

参数：`wxid`（必填）、`remark`（必填，空串=清除备注）、`dry_run`（默认 true）。

实现路线（按优先级尝试，命中即用）：
1. 反射微信自己的修改接口（`NetSceneModContactRemark` / `com.tencent.mm.model.*` 的 contact 更新方法）—— 走微信内部流程，DB 与通知都会同步。
2. 兜底：`rcontact` 直写 `conRemark` / `conRemarkPYFull` / `conRemarkPYShort` 三列 + 触发联系人缓存刷新。

为什么默认走 1：直写 `rcontact` 只改库，内存联系人缓存不刷新，聊天页/通讯录要等重启才更新，且可能被微信回写覆盖；且 `conRemarkPY*` 是拼音索引列，写错会让搜索错乱。

纪律：写完立刻回查 `rcontact`（同 `wx_send_*` 的落库回查），返回 `verified` 与实际三列值；不一致就报 `REMARK_NOT_PERSISTED`。

### 3.2 `wx_get_messages` 分页

现状只有 offset/limit 风格。改成游标式（`before_msg_id`），避免大 offset 扫描；并在返回里给 `nextCursor`。

### 3.3 DB 健壮性

- `DbGateway.query` 加超时（如 3s）与一次重试，捕获 `SQLiteDatabaseLockedException` 一类错误，返回 `DB_BUSY` 而不是 500。
- DB 游标缓存：目前每次都 `w41.i9.b() → t()` 走一遍反射，高频轮询下开销明显，加句柄缓存（注意微信切换账号要失效）。

## 4. 明确不做（M4 范围内）

- 收图/收视频 `.dat` 解密：密钥与微信版本强绑定，收益/成本不划算。
- 群管理（拉人/踢人/建群）：netScene 参数复杂且误操作代价高（会在真实群里产生动作），需要单独评审。
- MCP SSE 推送：先确认 Operit/Eta 客户端是否支持服务端 notification，再决定是否加 SSE 通道。

## 5. 迭代切分

| 迭代 | 内容 | 预估 | 交付物 |
|---|---|---|---|
| M4.1 | `wx_incremental` + `wx_watch` + 单测骨架 | 4–6h（CI 每轮 15min 是大头） | 0.3.x + 实测记录 |
| M4.2 | `wx_set_remark` + 消息游标分页 + DB 超时重试 | 3–5h | 0.4.x + 实测记录 |
| M5 | 协议层（resources/prompts/SSE）——先做客户端能力探明 | 待定 | 方案文档 |

## 6. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 长轮询堵主线程 | 微信 UI 卡顿/ANR | 后台睡、回主线程查；单实例锁；压力测试 `wx_status` 并发 |
| 轮询频率过高耗电 | 用户可感知 | 默认 800ms，`timeout_ms` 上限 60s，空闲退避 |
| hook 收消息点失效 | 实时性退化 | 纯轮询即可用，hook 只是加速 |
| 直写 `rcontact` 被微信覆盖 | 备注丢失 | 优先走微信接口；写完回查；失败明确报错 |
| DB 被微信写事务锁 | 查询偶发失败 | 超时 + 重试 + `DB_BUSY` 错误码 |

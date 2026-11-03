# lsp-what-chat

把微信变成 Eta 的 MCP 工具集：LSPosed 模块在微信进程内起一个本地 MCP Server，Eta 直接 `http://127.0.0.1:18765/mcp` 连过去，就能读会话/消息/联系人、发消息。

> 仅供个人自用与研究。自动发送类操作有账号风险，自行评估。

## 架构

```
┌──────────┐   MCP over HTTP   ┌────────────────────────────────────┐
│   Eta    │ ────────────────► │ [微信进程 com.tencent.mm]            │
│ (client) │  127.0.0.1:18765  │  LSPosed Module                     │
└──────────┘                   │   ├ HttpServer   (ServerSocket)     │
                               │   ├ McpDispatcher(双协议语义)        │
                               │   ├ ToolRegistry (JSON Schema)      │
                               │   └ WeChatBridge                    │
                               │        └ DbGateway (反射拿 SQLiteDatabase) │
                               └────────────────────────────────────┘
```

要点：

- **MCP Server 跑在微信进程里**。要访问的 `SQLiteDatabase`、`ClassLoader`、发送引擎都只存在于这个地址空间，跨进程只会多一层序列化。
- **不自己解密 `EnMicroMsg.db`**。反射拿微信进程内已经打开的 `SQLiteDatabase`，绕开 SQLCipher key 推导与 WAL 一致性问题。
- **协议双兼容**：同时满足 MCP `2025-11-25`（带 session 的 legacy）与 `2026-07-28`（无 session、要求 `resultType=complete`），Eta 的 `protocolMode=auto` 可一次命中。

## 当前状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 8.0.80 取库坐标（照抄 `com.wechat.wechatai` 1.1.8 的 `Lz2/l54;` 表：`w41.i9.b()` → `t()` → `f(sql,args,0)`） | v0.2.6 已实现，待 LSPosed 启用后验证 `dbProbe` |
| M1 | 8 个只读工具 + 双协议 MCP Server | 代码就绪，云端构建通过 |
| M2 | 发送（文本/图片/文件/引用）+ dry_run + 限流 | 文本已实测发送成功（0.2.10）：`filehelper` msgId=12478 `isSend=1`；图片/文件/引用待开工 |
| M3 | 分析向（活跃度/热词/上下文打包/CSV 导出） | 待开工 |

## 工具

| 名称 | 说明 | 只读 |
|---|---|---|
| `wx_status` | 模块/微信/数据库状态与探测报告 | ✅ |
| `wx_self` | 当前登录账号 | ✅ |
| `wx_list_conversations` | 会话列表（按最后消息时间倒序） | ✅ |
| `wx_get_messages` | 指定会话的最近消息（支持 beforeTs 翻页） | ✅ |
| `wx_search_messages` | 关键词搜索消息 | ✅ |
| `wx_search_contacts` | 搜索联系人 | ✅ |
| `wx_group_members` | 群成员与群主 | ✅ |
| `wx_send_text` | 发送文本（默认 `dry_run=true`，只构造不发送） | ❌ |
| `wx_send_limits` | 发送约束与当前限流用量 | ✅ |
| `wx_raw_sql` | 只读 SQL 直通（白名单表，默认不启用） | ✅ |

## 构建

推到 `main` 后由 GitHub Actions 自动构建，产物在 Actions → Artifacts → `whatchat-mcp-apk`。

- 仓库：<https://github.com/zqf1314/lsp-what-chat>
- 工作流：`.github/workflows/build.yml`（ubuntu-latest + JDK 17 + SDK 34 + Gradle 8.7）
- 签名：release 用 debug 密钥（指纹固定，可覆盖升级）；要换正式签名再加 keystore + CI secrets

命令行取最新产物：

```bash
gh run download -R zqf1314/lsp-what-chat -n whatchat-mcp-apk -D dist
```

> CI 注意：`android-actions/setup-android@v3` 的默认 `packages: tools platform-tools` 在新版 sdkmanager 里已无 `tools` 包，会直接报 `Failed to find package 'tools'`，所以 workflow 里显式写死
> `packages: 'platform-tools build-tools;34.0.0 platforms;android-34'`。

本地构建需要 JDK 17 + Android SDK 34（当前开发机没有 SDK，全程走云端）：

```bash
gradle :module:assembleRelease
```

## 安装

1. 安装 APK（release 用 debug 密钥签名，指纹固定，可覆盖升级）。
2. LSPosed 中启用 **WhatChat MCP**，作用域勾选 **微信**。
3. 强制停止微信并重新打开。
4. 校验：`adb shell curl -s http://127.0.0.1:18765/health` → `{"ok":true,...}`
   （没 curl 就用 `adb shell "toybox nc 127.0.0.1 18765"` 手动发一行请求，或直接看 `adb logcat -s WhatChatMCP`）
5. 打开模块 App 可看到 MCP 地址与运行状态。

## 在 Eta 中接入

| 字段 | 值 |
|---|---|
| 名称 | WeChat |
| URL | `http://127.0.0.1:18765/mcp` |
| 协议 | `auto` |
| 鉴权 | `none` |

添加后刷新工具列表，勾选需要的 `wx_*`。建议先只勾 `wx_status` 验证通路。

## 排障

| 现象 | 排查 |
|---|---|
| 端口连不上 | LSPosed 作用域是否含微信；微信是否被强制重启；`logcat -s WhatChatMCP` 是否有 `MCP http listening` |
| 日志里 `NoSuchMethodError: Application#attachBaseContext` | Android 16 把该方法下沉到 `ContextWrapper`，v0.2.0 起已改成「按名 hook + Instrumentation 兜底」，升级即可 |
| `wx_status` 里 `dbReady=false` | 看 `data.dbProbe` 的 `candidates`，M0 坐标还没对上，把候选贴到 issue |
| 工具返回 `TOOL_ERROR` | 多半是表字段随微信版本变化，用 `wx_raw_sql` 手动确认字段名 |
| 微信启动变慢 | 只探测一次、跑在后台线程；如仍明显，把 `DbGateway.probe()` 改为按需触发 |
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 之前用 CI 临时 debug key 签的包装不上了：先 `pm uninstall com.whatchat.mcp` 再装，之后固定签名可覆盖升级 |

## 踩坑记录

| 坑 | 表现 | 处理 |
|---|---|---|
| `Application#attachBaseContext` 在 Android 16 上找不到 | `NoSuchMethodError: android.app.Application#attachBaseContext[class android.content.Context]#exact`，hook 链直接断，服务永不启动 | 改用 `XposedBridge.hookAllMethods` 按方法名挂 + `ContextWrapper` 回退 + `Instrumentation.callApplicationOnCreate` 兜底 |
| CI 每次生成新 debug keystore | 两次构建签名不同，覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 仓库不存 keystore；用 `SIGNING_KEYSTORE_B64` + `SIGNING_STORE_PASS` 两个 Actions secret，构建时解码成固定 keystore |
| 卸载重装会丢 LSPosed 启用状态 | `modules_state` / `scope` 行被清空，模块回到未启用 | 固定签名后正常覆盖升级即可，别再卸载 |
| UI 读不到微信进程状态 | 模块 Activity 在独立进程，`WxEnv` 永远 unknown | 改成 TCP 探测 `127.0.0.1:18765`，微信版本走 `PackageManager` 读 |
| 主线程 `Socket.connect` | 抛 `NetworkOnMainThreadException`，探测恒为 false | 探测放后台线程，结果回 `runOnUiThread` |
| 微信不用 AOSP 的 `SQLiteDatabase` | 扫 `android.database.sqlite.SQLiteDatabase` 字段一个都扫不到（`fields=0`），hook `com.tencent.wcdb.database.SQLiteDatabase` 的构造器/rawQuery 也挂不上（微信 8.0.80 不走那些入口） | 改成照抄 `com.wechat.wechatai` 1.1.8 的反射链：`w41.i9.b()` → `t()` → `f(sql,args,0)`（见下节坐标表） |
| 覆盖安装后模块又“无作用” | 日志里没有 `MCP http listening`，`modules_state.enabled=0`（LSPosed 把模块判为未启用） | 到 LSPosed 管理器把 WhatChat MCP 勾上、作用域勾微信，再强制停止微信重开；本仓库不动 `modules_config.db` |
| Eta 报“不支持并发” | 一条 keep-alive 连接上并行发多个 `tools/call`，旧实现读一个处理完再读下一个，慢调用把后面全堵住 | 0.2.7 起单连接内并发执行、按序回写（HTTP/1.1 只要求响应有序）；实测 8 并发在 7ms 内全部返回 |
| 跑一阵后端口不再响应 | 客户端连上但数据堆在接收队列，`accept` 长期不返回；`kill -3` 抓到 `whatchat-http` 卡在 `PlainSocketImpl.socketAccept` | 0.2.8 给 `ServerSocket` 设 30s `soTimeout`（超时 continue）+ 连接改走线程池，避免线程随连接数堆积 |
| **端口连上但一个字节都不回**（更容易复现） | 微信进程被系统冻住：`/proc/<pid>/task/<accept tid>/wchan = do_freezer_trap`、`/sys/fs/cgroup/apps/uid_10402/cgroup.freeze = 1`、`ss` 里连接停在 accept 队列（`ESTAB` 无 owner、`Recv-Q` 有字节） | 这是 Android 的 cached-app freezer，不是服务端 bug。把微信拉回前台/分屏，或对微信关掉省电限制（HyperOS：省电策略→无限制 + 最近任务上锁），进程解冻后健康检查立刻恢复 200（实测 1.9ms） |

## 与参考实现的关系

工具语义参考了 `com.wechat.wechatai`（微信 Agent）的能力面，但**本仓库为独立实现**，不含其代码：

- 它自带 Agent 运行时（Koog）+ MCP **客户端**；本项目只做 MCP **服务端**，把能力交给 Eta。
- 它的工具集含 Shell/root、文件系统、设备信息（约 80 个），本项目刻意只保留微信相关能力——Eta 本身已有终端与文件能力。

### 取库坐标（微信 8.0.80，来自 1.1.8 的 `Lz2/l54;` 常量表）

参考实现把「按微信版本变动的混淆名」写成一张表（`Lz2/l54` 的字段 + 注解名），`Lz2/x16` 是它的 DB 网关。我们只抄 DB 这一小块：

| 用途 | 常量/调用 |
|---|---|
| 方式 B（不需要 uin，本项目采用） | `Class.forName("w41.i9").b()` → 实例 → `实例.t()` → db |
| 方式 A（需要 uin，暂未用） | `Class.forName("ai5.d0").newInstance()` → `实例.R(uin)`，由微信自己打开 `EnMicroMsg.db` |
| 查询 | `db.f(String sql, String[] selectionArgs, int 0)` → `Cursor` |
| 就绪判断 | `db` 非 null；`DbGateway.probeReport` 里的 `wxdb=` 段是解析诊断 |

对应实现：`module/src/main/java/com/whatchat/mcp/bridge/WxDb.kt`（`WxEnv.callOnMain` 内解析与查询，避免微信 Core 的线程约束）；`DbGateway` 优先走它，`SqliteHook` 保留为兜底。

### 发送消息坐标（微信 8.0.80，已移植到 `WxSender.kt`）

参考实现的发消息是「自己造消息对象 → 交给微信网络核心发」，全程反射，不碰 UI：

| 用途 | 常量/调用 |
|---|---|
| 文本消息对象 | `Class.forName("q61.s0")`；8.0.80 实测只有两个 6 参构造：`(String,String,int,int,Object,String)`（第 5 参接 HashMap，@ 列表走这条）与 `(String,String,int,int,long,String)`（无附加信息）。参考实现传 HashMap，故优先 Object 变体 |
| @ 人 | 上面那个 HashMap 放 `"atuserlist" -> "<![CDATA[wxid1,wxid2]]>"` |
| 提交 | `Class.forName("w41.i9")`（与取库同一个内核类）→ 静态 `e()` 拿网络核心实例 → 对实例调 `i(netScene, Integer(0))` |
| 其它消息 | 图片/文件/引用/名片各有独立的 lambda（`sendWeChatImageMessage` / `sendWeChatFileMessage` / `sendWeChatQuoteMessage` / `sendWeChatContactCardMessage`），套路一致 |

判空/长度校验参考实现也做了：`sendId` 与 `text` 不能为空、正文上限 4000 字（`0xfa0`），超限直接返回错误 JSON。

本项目落地时的取舍：

- 构造与提交都放 `WxEnv.callOnMain` 里执行（参考实现整条链也在主线程），避免微信 Core 的线程断言。
- `dry_run` 默认 `true`：只走「造对象」这一步并回显真实类名，用于校验坐标；`dry_run=false` 才提交。
- 限流：两条之间 ≥1.5s，每分钟 ≤20 条（`wx_send_limits` 可查当前窗口用量）。
- 失败时日志带诊断串：`ctors=`（六个构造器签名）、静态方法表、`i/2` 候选方法表，坐标漂了能直接看出改成什么。

### 参考实现的能力面（1.1.8，共 6 个 ToolSet）

| ToolSet | 工具数 | 内容 | 本项目的取舍 |
|---|---|---|---|
| `WeChatDbToolSet` | ~43 | 会话/消息/联系人/群成员/统计/schema/CSV 导出/原始只读 SQL | 已覆盖核心 8 个，长尾按需补 |
| `SendMessageToolSet` | 5 | 文本、图片、文件、引用、名片 | **M2 要抄的全部** |
| `WechatToolSet` | 2 | 联系人备注名 读/写 | 写操作，放 M2 之后 |
| `FileToolSet` / `ShellToolSet` / `AppTools` | 16 / 17 / 22 | 文件、root shell、设备信息/计算器 | 不纳入（Eta 自带这些能力） |

## 免责

- 仅用于个人账号自用。发送消息是真实操作，模型判断错误会发错内容，务必先开 `dry_run`。
- 微信版本升级会导致内部坐标失效，模块需跟着适配。
- 不做任何绕过风控、批量骚扰的用途。
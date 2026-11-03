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
| M2 | 发送（文本/图片/文件/引用/名片）+ dry_run + 限流 | 全部实测通过（`filehelper`）：文本 `msgId=12478`；图片 `12735` `type=3`；文件 `12741` `type=0x41000031`（`WXFileObject→WXMediaMessage→k0.F`）；引用 `12742` `type=49`（`refermsg` appmsg，`q61.t1` builder）；名片 `12744` `type=42`，引用名片回归 `12745` `type=49` |
| M3 | 分析向（活跃度/热词/上下文打包/CSV 导出） | 全部实测通过（0.2.29）：`wx_activity` 7 天 `total=3601`、`byDay` 逐日 `2026-10-28…11-04`、`topTalkers` 首位 `1985` 条；`wx_hot_words`（群 3 天采样 300 → `捂脸/https/后台`）；`wx_context`（`filehelper` anchor `12745` 带出 `12742/12743/12744`）；`wx_export_csv` `rows=48 bytes=23698`，私有 + 外部双路径 |

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
| `wx_send_image` | 发送本地图片（`type=3`，落库回查 `verified/msgId`） | ❌ |
| `wx_send_file` | 发送本地文件（`WXFileObject→WXMediaMessage→k0.F`，落库回查） | ❌ |
| `wx_send_quote` | 引用回复（`refermsg` appmsg，`type=49`，走 `q61.t1` builder） | ❌ |
| `wx_send_card` | 发送联系人名片（`type=42`，昵称缺省从 `rcontact` 补） | ❌ |
| `wx_send_limits` | 发送约束与当前限流用量 | ✅ |
| `wx_probe_send` | 反射勘察发送核方法表（诊断用） | ✅ |
| `wx_activity` | 活跃度统计：最近 N 天总量/收发分列、逐日分桶、Top 会话（`createTime` 毫秒对齐） | ✅ |
| `wx_hot_words` | 聊天热词：中文 2~4 字滑窗 + 英文整词 + 停用词表 | ✅ |
| `wx_context` | 上下文打包：按 `around` msgId（或最新一条）取前 N/后 M 条，群聊自动剥 `wxid:\n` 前缀 | ✅ |
| `wx_export_csv` | 导出最近 N 天消息为 CSV（取最近 `limit` 条后升序落盘，私有 + `Android/data` 外部目录各一份） | ❌ |
| `wx_raw_sql` | 只读 SQL 直通（白名单表，默认不启用） | ✅ |

## 让微信不被冻结（重要，否则 MCP 会“连上不回”）

模块跑在微信进程里。微信一旦退到后台被判为 cached，Android 会把**整个进程**冻住（freezer）：
端口还在 LISTEN，但 `accept` 线程拿不到 CPU，客户端连上后一个字节都收不到 —— 表现就是请求挂死。

现场判断：

```bash
cat /proc/<微信pid>/task/<accept tid>/wchan      # do_freezer_trap = 被冻了
cat /sys/fs/cgroup/apps/uid_10402/cgroup.freeze  # 1 = 冻结
```

三种解法（从省事到彻底）：

1. 调 MCP 前把微信拉回前台或分屏；
2. HyperOS：微信 → 省电策略 → 无限制，最近任务卡片上锁；
3. 有 root（KernelSU/Magisk）：用 `tools/wx-keepalive.sh`

```bash
sh tools/wx-keepalive.sh once        # 解冻一次
sh tools/wx-keepalive.sh watch 5     # 常驻巡检，被冻就解冻
# 开机自启：cp 到 /data/adb/service.d/ 并 chmod 755，末尾加一行 ( sh "$0" watch 5 & )
```

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
| 发消息“dry_run 也真发出去了” | 微信 8.0.80 的 `q61.s0` **构造器本身就把消息入队**（构造后不调任何方法，DB 里也有 `isSend=1` 的记录） | dry-run 改成纯反射能力探测（不 new 实例）；限流检查挪到构造之前；构造后再补 `doScene` 也不产生重复消息 |
| **发图片返回 submitted 却查不到消息** | `wx_send_image` 命中发送核心、`submitted=true`、`costMs` 十几毫秒，但 `message` 表没有新行；`DupCheck-1.db` 有打开痕迹 | 不是坐标问题，是**分区存储**：`/sdcard` 走 FUSE，别的 app 或 `adb shell` 建的文件（即便 `chmod 644`、即便拷进 `com.tencent.mm` 目录）微信进程都读不到，发送核心**静默失败不报错**。换成微信自己目录下的图（`.../cache/*/openapi_cache/*.png`）立刻成功：`msgId=12735 type=3` 带 `<img aeskey/md5/length>`。0.2.14 起 `WxSender` 先做 `File.canRead()` 预检，`SendTools` 发后 2.5s 轮询 `message` 表返回 `verified/msgId/msgType` |
| `su <uid> sh -c` 验证不了权限 | 以微信 uid 执行 `head` 仍报 `Permission denied`，容易误判 | `su` 起的进程不在微信的 mount namespace 里，FUSE 判定不适用。要判断「微信能不能读」只有两条路：在微信进程内 `File.canRead()`（模块已内置），或直接看发送后有没有入库 |
| **`message.createTime` 是毫秒**（如 `1791352171000`） | `wx_activity` 的 `byDay` 全是 `"day": ""` 且只有 1 行；`total=12283` 看着像对的，其实 `createTime >= since(按秒算)` 恒真 → 统计的是**全库**，`days` 参数完全失效；`strftime('%Y-%m-%d', createTime, 'unixepoch')` 因数值超范围直接返回 NULL | 时间一律按毫秒：`sinceMs = System.currentTimeMillis() - days*86400_000L`；分天用 `strftime('%Y-%m-%d', createTime/1000 + 28800, 'unixepoch')`（先除 1000 再补 8h）。微信内置 sqlite 3.27.2 对 `strftime` 修饰符链敏感，别在 `'unixepoch'` 后再叠 `'localtime'`，叠了就整体 NULL。修后 7 天 `total=3601`、`byDay` 逐日正常 |

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

- **铁律：`q61.s0` 构造即发送。** 8.0.80 实测只要 `newInstance` 就会把消息入队并发出去（构造后什么都不做，DB 里也能查到该条 `isSend=1`），所以「构造但不提交」不能当 dry-run 用。
- 因此 `dry_run=true`（默认）只做**反射能力探测**：加载 `q61.s0`、列出可用构造器、检查 `w41.i9.e()` 是否还在，不实例化、不发送；`dry_run=false` 才真正构造并发送。
- 限流检查放在构造**之前**（构造即发送，晚一步就拦不住）。
- 构造后再按参考实现补一次 `doScene`（`i(net,int)`），实测不会产生重复消息；匹配不到也不影响发送，只在日志里记一笔。
- 构造与提交都在 `WxEnv.callOnMain` 里执行（参考实现整条链也在主线程），避免微信 Core 的线程断言。
- 失败时日志带诊断串：`ctors=`（真实构造器签名）、静态方法表、`i/2` 候选方法表，坐标漂了能直接看出改成什么。

实测记录（0.2.10/0.2.11，8.0.80）：

| 场景 | 结果 |
|---|---|
| 纯文本发 `filehelper` | `msgId=12478/12481`，`isSend=1`，`msgSvrId` 非空（确实过服务器） |
| 连续两条 | 第二条被限流拦下：`发送过快，请 1481ms 后再试` |
| 带 `at_wxids` | 命中 `(String,String,int,int,Object,String)` 构造器（HashMap 那条），纯文本命中 `(String,String,int,int,long,String)` |
| 发文件 `NoSuchMethodException` | `WXMediaMessage` 没有 `(WXFileObject)` 构造器、`WXFileObject` 也没有 `(String)` 构造器 | 改自适应反射：`declaredConstructors` 找单参可赋值构造器，否则无参 + 沿继承链找字段写 `mediaObject`/`title`（`WxSender.allFields/senderInstance`） |
| 引用落库成 `type=1`（原始 XML 被当纯文本） | 手搓 appmsg XML 走 `q61.s0`/`q61.s1` 都发不出去：`s1` 没有 6 参构造器（只有 `()`），它是 builder | 照微信自己的 `ChattingBrandServiceHelper`：`q61.t1.a(talker)` → `g(talker)` → `e(xml)` → `h(49)` → `b()`（内部 `executeByPPC`），实测 `type=49` |
| `buildOnMain` 丢了 `msgClass` | 引用明明切了 msgClass，实际仍走默认 `q61.s0` | `buildOnMain` 必须把 `msgClass` 透传进 `buildNetScene` |

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
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
| M0 | 8.0.78 坐标勘察（DbGateway.probe 扫描 `SQLiteDatabase` 字段） | 代码就绪，APK 已装机，待启用模块后验证 `dbProbe` |
| M1 | 8 个只读工具 + 双协议 MCP Server | 代码就绪，云端构建通过 |
| M2 | 发送（文本/图片/文件/引用）+ dry_run + 限流 | 待开工 |
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
| `wx_status` 里 `dbReady=false` | 看 `data.dbProbe` 的 `candidates`，M0 坐标还没对上，把候选贴到 issue |
| 工具返回 `TOOL_ERROR` | 多半是表字段随微信版本变化，用 `wx_raw_sql` 手动确认字段名 |
| 微信启动变慢 | 只探测一次、跑在后台线程；如仍明显，把 `DbGateway.probe()` 改为按需触发 |

## 与参考实现的关系

工具语义参考了 `com.wechat.wechatai`（微信 Agent）的能力面，但**本仓库为独立实现**，不含其代码：

- 它自带 Agent 运行时（Koog）+ MCP **客户端**；本项目只做 MCP **服务端**，把能力交给 Eta。
- 它的工具集含 Shell/root、文件系统、设备信息（约 80 个），本项目刻意只保留微信相关能力——Eta 本身已有终端与文件能力。

## 免责

- 仅用于个人账号自用。发送消息是真实操作，模型判断错误会发错内容，务必先开 `dry_run`。
- 微信版本升级会导致内部坐标失效，模块需跟着适配。
- 不做任何绕过风控、批量骚扰的用途。
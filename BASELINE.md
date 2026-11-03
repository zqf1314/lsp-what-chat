# BASELINE — M3 基准（0.2.30）

> 这是 M0–M3 全部落地后的**稳定基准**。后续 M4 起的改动以此为回归基线：
> 任何新功能上线前，先确认下表的工具与不变量没被破坏。

## 1. 标识

| 项 | 值 |
|---|---|
| 基准 tag | `v0.2.30-m3-baseline` |
| versionName / versionCode | `0.2.30` / `32` |
| 功能代码 commit | `77f26c1`（`polish(m3): 热词降噪…`） |
| 文档 commit | `e4feb2e`（README 打磨记录 + `.gitignore`） |
| CI run | `41717347038`（`whatchat-mcp-apk` 产物） |
| 安装包 | `releases/whatchat-mcp-0.2.30-vc32-release.apk`（本地归档，`*.apk` 不入库） |
| APK sha256 | `2ff886422fb3205bc5c27d8ca2c9fa8c9fe9f681e7996e2ee21617cc037b92d7` |
| APK 大小 | 699,033 字节 |

## 2. 运行环境

- 微信 `8.0.80`，LSPosed 作用域勾微信，模块注入同进程。
- MCP HTTP：`127.0.0.1:18765`，协议 `2026-07-28` / `2025-11-25`。
- 数据库：微信内置 sqlite `3.27.2`，走 wechatai 反射链 `w41.i9.b()` → `t()` → `f(sql,args,0)`（`DbGateway` 封装，强制主线程）。
- 进程保活：微信被 Android 冻住时 MCP 会"连上不回"，需关省电限制/上锁（见 README）。

## 3. 工具清单（19 个）

**只读 / 检索（10）**：`wx_status`、`wx_self`、`wx_list_conversations`、`wx_get_messages`、`wx_search_messages`、`wx_search_contacts`、`wx_group_members`、`wx_send_limits`、`wx_probe_send`、`wx_raw_sql`

**发送（5，默认 `dry_run=true`）**：`wx_send_text`、`wx_send_image`、`wx_send_file`、`wx_send_quote`、`wx_send_card`

**分析（4）**：`wx_activity`、`wx_hot_words`、`wx_context`、`wx_export_csv`

## 4. 基准实测矩阵

全部在本机真机跑过（`tools/mcp_call.py`），发送类统一打 `filehelper`。

| 工具 / 场景 | 基准结果 |
|---|---|
| `wx_status` | 19 个工具在线，`dbProbe` 正常 |
| `wx_send_text` | `msgId=12478`，`type=1` |
| `wx_send_image` | `msgId=12735`，`type=3`，带 `aeskey/md5/length` |
| `wx_send_file` | `msgId=12741`，`type=0x41000031`（`WXFileObject→WXMediaMessage→k0.F`） |
| `wx_send_quote` | `msgId=12742`，`type=49`（`refermsg` appmsg，`q61.t1` builder） |
| `wx_send_card` | `msgId=12744`，`type=42`；引用名片回归 `12745` `type=49` |
| `wx_activity`（7 天） | `total=3601`、`sent=524`、`recv=3077`；`byDay` 逐日 `2026-10-28…11-04`；`topTalkers` 首位 `1985` 且带 `isChatroom` |
| `wx_activity`（单会话） | `filehelper` 30 天 `total=48 sent=48 recv=0` |
| `wx_hot_words` | 群 3 天采样 300 → `distinct=3786`，榜首 `后台 9 / 有没 7 / 有没有 7`（`com`、`捂脸`、`https` 已被降噪剔除） |
| `wx_context` | `filehelper` anchor `12745` → `12743/12744/12745`，每条带 `time`（`2026-11-04 04:21:05` 形态）；anchor `12743` `before=0/after=2` → 正确取到 `12744/12745` |
| `wx_export_csv` | `filehelper` 30 天 `limit=20` → `rows=20 bytes=10021`，表头 `msgId,type,isSend,createTime,talker,content,time`，末行为最新 `12745`（取最近 N 条后升序），私有 + `Android/data` 双路径 |

## 5. 不可破坏的不变量

后续改动必须继续满足这些约定，否则算回归：

1. **`message.createTime` 是毫秒**。任何时间比较/格式化按毫秒；分天用 `strftime('%Y-%m-%d', createTime/1000 + 28800, 'unixepoch')`，不要在 `'unixepoch'` 后再叠 `'localtime'`。
2. **消息排序键是 `(createTime, msgId)`**，区间查询用复合比较，不能只比 `createTime`。
3. **发送类工具默认 `dry_run=true`**，且发后必须回查消息库校验 `type`（`wx_send_quote` 必须是 49、`wx_send_card` 必须是 42，不符直接报错）。
4. **消息类型码**：文本 `1`、图片 `3`、语音 `34`、名片 `42`、引用/appmsg `49`、文件 `0x41000031`。
5. **引用/名片走 `q61.t1` builder**（`a(talker)` → `g` → `e` → `h(type)` → `b()`），不要 `new q61.s0`（构造即入队）。
6. **CSV 列顺序固定**：`msgId,type,isSend,createTime,talker,content,time`（只在末尾扩展，不改前面列）。
7. **`wx_context` 的 `time` 为东八区 `yyyy-MM-dd HH:mm:ss`**。
8. **热词降噪**：`[表情]` 占位符、裸 URL、文件后缀/域名类英文（`com`/`jpg`/`https` 等）不入词；同频按字典序排序保证稳定。

## 6. 基准自检（换环境后先跑这几条）

```bash
# 1) 服务在线
python3 -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:18765/health',timeout=8).read().decode())"

# 2) 工具数应为 19，且版本 0.2.30
python3 tools/mcp_call.py wx_status '{}'

# 3) 时间单位是否还对（byDay 不应出现空 day）
python3 tools/mcp_call.py wx_activity '{"days":3,"top":3}'

# 4) 上下文时间字段
python3 tools/mcp_call.py wx_context '{"talker":"filehelper","before":2,"after":0}'

# 5) CSV 双路径 + time 列
python3 tools/mcp_call.py wx_export_csv '{"talker":"filehelper","days":7,"limit":10}'
```

## 7. 回滚

```bash
git fetch --tags
git checkout v0.2.30-m3-baseline
./gradlew :module:assembleRelease        # 或用归档的 releases/whatchat-mcp-0.2.30-vc32-release.apk
```

覆盖安装注意：必须用同一签名（CI 侧 `SIGNING_*` secrets），否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；不要卸载重装（会丢 LSPosed 启用状态）。

## 8. 已知限制（基准时点）

- 无推送通道：只能客户端轮询/长轮询（M4 处理）。
- 收图/收视频是 `content` 里的 XML 元数据，不做 `.dat` 解密。
- 群管理（拉人/踢人/建群）未实现。
- `wx_raw_sql` 默认不启用，且只允许白名单表。
- CI 单轮 15–20 分钟，迭代节奏受此约束。

## 9. 基准之后的增量

| 版本 | 变更 | 实测 |
|---|---|---|
| 0.2.31 | 新增 UI 工具（进程内 startActivity，不需要 root） | `profile` 到位 |
| 0.2.32 | 移除 `add`/`room`（直连会把微信带崩） | `profile` 稳定 |
| 0.2.33 | 收敛为单一工具 `wx_open_profile`：只开资料页，删除 `mode`/`sayhi`/`force_get_contact`/`verify` | `topActivity=ContactInfoUI`、`arrived=true` |

> 工具数 20；新增工具不改变第 5 节任何不变量。

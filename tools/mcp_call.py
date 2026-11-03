#!/usr/bin/env python3
"""WhatChat MCP 的最小命令行客户端（走 Streamable HTTP，无状态模式）。

用途：在 proot 侧直接调模块的 MCP 工具，绕开被 freezer 卡死的 adb shell。

用法：
    python3 tools/mcp_call.py list
    python3 tools/mcp_call.py wx_status '{}'
    python3 tools/mcp_call.py wx_send_image '{"talker":"filehelper","path":"/sdcard/Download/wc_test.png","dry_run":true}'

第二个参数是 JSON 对象；省略则为 {}。
"""
import json
import sys
import urllib.request

ENDPOINT = "http://127.0.0.1:18765/mcp"
PROTO = "2026-07-28"


def _post(payload: dict, timeout: int = 90) -> tuple[dict, str | None]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        ENDPOINT,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Mcp-Protocol-Version": PROTO,
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", "replace")
        sid = resp.headers.get("Mcp-Session-Id")
    # 兼容 SSE 包装：取最后一行 data:
    if raw.lstrip().startswith("event:") or "\ndata:" in raw:
        datas = [ln[5:].strip() for ln in raw.splitlines() if ln.startswith("data:")]
        if datas:
            raw = datas[-1]
    return json.loads(raw), sid


def rpc(method: str, params: dict, rid: int = 1, timeout: int = 90) -> dict:
    resp, _ = _post(
        {"jsonrpc": "2.0", "id": rid, "method": method, "params": params},
        timeout=timeout,
    )
    if "error" in resp:
        raise SystemExit("RPC error: " + json.dumps(resp["error"], ensure_ascii=False))
    return resp.get("result", {})


def call_tool(name: str, args: dict, timeout: int = 90) -> dict:
    return rpc(
        "tools/call",
        {"name": name, "arguments": args, "_meta": {"protocolVersion": PROTO}},
        timeout=timeout,
    )


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    cmd = sys.argv[1]

    if cmd == "list":
        res = rpc("tools/list", {"_meta": {"protocolVersion": PROTO}})
        for tool in res.get("tools", []):
            print(tool.get("name"))
        return

    args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    res = call_tool(cmd, args)
    # 优先打印工具返回的结构化 JSON
    content = res.get("content") or []
    for block in content:
        if block.get("type") == "text":
            text = block.get("text", "")
            try:
                print(json.dumps(json.loads(text), ensure_ascii=False, indent=2))
            except json.JSONDecodeError:
                print(text)
            continue
        print(json.dumps(block, ensure_ascii=False))


if __name__ == "__main__":
    main()

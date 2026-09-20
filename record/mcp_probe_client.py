# -*- coding: utf-8 -*-
"""最小 MCP 客户端：连 IDE 内置 MCP Server 的 Streamable HTTP 端点，调一个只读工具并打印结果。"""
import json, sys, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:64342"
TOOL = sys.argv[2] if len(sys.argv) > 2 else "sqlv_explain"
ARGS = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}

def post(path, payload, sid=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json",
                                          "Accept": "application/json, text/event-stream"})
    if sid:
        req.add_header("Mcp-Session-Id", sid)
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read().decode("utf-8", "replace")
        return r.headers.get("Mcp-Session-Id"), body

sid, body = post("/stream", {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                          "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                                     "clientInfo": {"name": "probe", "version": "1"}}})
print("initialize:", body[:200])
post("/stream", {"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
_, body = post("/stream", {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                        "params": {"name": TOOL, "arguments": ARGS}}, sid)
print("---- tools/call ----")
print(body[:3000])

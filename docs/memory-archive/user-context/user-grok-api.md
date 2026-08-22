---
name: user-grok-api
description: 用户的 grok API 中转服务（api.openachieve.asia）——20 个模型清单、9
  个对话模型实测可用、Cloudflare 需浏览器 UA
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_7f85ff5a-d550-415b-9244-c23ea5f64189
---

用户提供的 OpenAI 兼容中转 API：`https://api.openachieve.asia/v1`，key 格式 `g2a_...`（完整 key 用户 2026-08-15 在会话中提供，需要时向用户索取或查对话记录）。

实测（2026-08-15，浏览器 UA 下）：
- `/v1/models` 共 20 个模型。9 个对话模型全部实测可用：grok-4.5 / grok-4.6 / grok-4.3 / grok-chat-fast / grok-composer-2.5-fast / grok-build-0.1 / grok-4.20-0309-reasoning / grok-4.20-0309-non-reasoning / grok-4.20-multi-agent-0309。
- 其余 11 个为媒体/语音模型（grok-imagine-image* 文生图、grok-imagine-video* 文生视频、grok-stt 语音转文字、grok-voice* 语音），走独立端点，未实测。

⚠️ Cloudflare 风控坑：默认 Python urllib UA 调 `/chat/completions` 被拒（HTTP 403 错误码 1010）；必须带浏览器 UA（如 `Mozilla/5.0 ... Chrome/126.0`）才能通过。`/v1/models` 列表请求不受影响。

相关：[[user-local-proxy]]（网络受限时可经代理访问外部服务）。

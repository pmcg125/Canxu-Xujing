# 本地模拟 API

此服务只返回固定文本，不调用模型或外部服务。只监听电脑的 `127.0.0.1:8765`；需要 Python 3，无额外依赖。

在 PowerShell 中运行：

```powershell
python D:\Canxu-project\Xujing\tools\mock_openai.py
```

Android 模拟器先执行 `adb -s emulator-5554 reverse tcp:8765 tcp:8765`，然后在残叙·残念中将 API 基础 URL 设为 `http://127.0.0.1:8765/v1`、模型设为 `xujing-mock`，无需 API 密钥。会话也需要选择此连接。流式开关可分别验证 SSE 与普通响应。查看服务状态：`http://127.0.0.1:8765/health`。服务器窗口按 Ctrl+C 停止；移除转发可执行 `adb -s emulator-5554 reverse --remove tcp:8765`。

如服务以隐藏的后台进程运行，可用 `Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 8765 -State Listen` 查看 `OwningProcess`，确认进程后用 `Stop-Process -Id <进程号>` 停止。重启服务后已有的捕获记录会继续追加。

符合 JSON 格式的聊天请求及校验结果追加到 `tools/mock-api-captures/requests.jsonl`。内容包含提示词和聊天记录，请自行保管或删除；请求头（包括 Authorization）不会记录，捕获文件被 Git 忽略。错误的 JSON 只返回错误，不记录正文。流式输出按片段延时返回，并以 `[DONE]` 结束。

可用 `--port` 改端口、`--capture` 指定捕获文件、`--chunk-delay` 调整每片延时。模型列表为 `GET /v1/models`，聊天端点为 `POST /v1/chat/completions`；这里只校验本应用所需的基本消息格式，并非完整的 OpenAI API 实现。

#!/usr/bin/env python3
"""Local, deterministic OpenAI chat-completions stub; no network upstream."""

import argparse
import json
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY = 2 * 1024 * 1024
REPLY = "【模拟回复】你好！这是一段用于验证流式输出的固定文本。"
MODEL = "xujing-mock"
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "XujingMock/1.0"
    sys_version = ""

    def log_request(self, code="-", size="-"):
        # Do not print request lines, query strings, headers or bodies.
        print(f"[mock] HTTP {code}", flush=True)

    def log_message(self, fmt, *args):
        pass

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def error_json(self, status, message):
        self.respond(status, {"error": {"message": message, "type": "invalid_request_error"}})

    def do_GET(self):
        if self.path == "/v1/models":
            self.respond(200, {"object": "list", "data": [{"id": MODEL, "object": "model", "created": 0, "owned_by": "local-mock"}]})
        elif self.path == "/health":
            self.respond(200, {"status": "ok", "service": "local-mock"})
        else:
            self.error_json(404, "Unknown endpoint")

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.close_connection = True
            self.error_json(404, "Unknown endpoint")
            return
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.close_connection = True
            self.error_json(411, "A valid Content-Length is required")
            return
        if length < 1 or length > MAX_BODY:
            self.close_connection = True
            self.error_json(413, "Request body must be between 1 byte and 2 MiB")
            return
        try:
            raw = self.rfile.read(length).decode("utf-8")
            request = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.error_json(400, "Request must be valid UTF-8 JSON")
            return
        error = self.validate(request)
        record = {"received_at": datetime.now(timezone.utc).isoformat(), "path": "/v1/chat/completions", "valid": error is None, "error": error, "body": request}
        with LOCK:
            with self.server.capture.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
        if error:
            self.error_json(400, error)
            return
        request_id = "chatcmpl-mock-" + uuid.uuid4().hex
        created = int(time.time())
        base = {"id": request_id, "created": created, "model": request["model"]}
        if not request.get("stream", False):
            self.respond(200, {**base, "object": "chat.completion", "choices": [{"index": 0, "message": {"role": "assistant", "content": REPLY}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}})
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def event(delta, finish=None):
            value = {**base, "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(("data: " + json.dumps(value, ensure_ascii=False) + "\n\n").encode("utf-8"))
            self.wfile.flush()

        try:
            event({"role": "assistant"})
            for start in range(0, len(REPLY), 4):
                event({"content": REPLY[start:start + 4]})
                time.sleep(self.server.chunk_delay)
            event({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # The client may stop generation before all chunks are sent.

    @staticmethod
    def validate(value):
        if not isinstance(value, dict):
            return "JSON body must be an object"
        if not isinstance(value.get("model"), str) or not value["model"].strip():
            return "model must be a non-empty string"
        messages = value.get("messages")
        if not isinstance(messages, list) or not messages:
            return "messages must be a non-empty array"
        for i, message in enumerate(messages):
            if not isinstance(message, dict) or message.get("role") not in ("system", "developer", "user", "assistant", "tool"):
                return f"messages[{i}].role is invalid"
            content = message.get("content")
            if not isinstance(content, str):
                if not isinstance(content, list) or not content:
                    return f"messages[{i}].content must be a string or a non-empty content-part array"
                for j, part in enumerate(content):
                    if not isinstance(part, dict):
                        return f"messages[{i}].content[{j}] is invalid"
                    kind = part.get("type")
                    if kind == "text":
                        if not isinstance(part.get("text"), str):
                            return f"messages[{i}].content[{j}].text must be a string"
                    elif kind == "image_url":
                        image = part.get("image_url")
                        if not isinstance(image, dict) or not isinstance(image.get("url"), str):
                            return f"messages[{i}].content[{j}].image_url.url must be a string"
                    else:
                        return f"messages[{i}].content[{j}].type is invalid"
        if "stream" in value and not isinstance(value["stream"], bool):
            return "stream must be a boolean"
        return None


def main():
    parser = argparse.ArgumentParser(description="Run a local mock OpenAI chat API; no model or external connection.")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--capture", type=Path, default=Path(__file__).resolve().parent / "mock-api-captures" / "requests.jsonl")
    parser.add_argument("--chunk-delay", type=float, default=0.12, help="seconds between streamed chunks")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 <= args.chunk_delay <= 10:
        parser.error("port must be 1-65535 and chunk delay 0-10 seconds")
    args.capture.parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.capture = args.capture.resolve()
    server.chunk_delay = args.chunk_delay
    print(f"Local API: http://127.0.0.1:{args.port}/v1 | model: {MODEL}", flush=True)
    print(f"Captured request bodies: {server.capture} (contains prompt text)", flush=True)
    print("Stop with Ctrl+C.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

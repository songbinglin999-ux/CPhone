#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
远手（com.yuanhou.hand）0.1.9 局域网指令通道的命令行客户端，只用 Python 3 标准库。
只在家里局域网使用：Mac 和备用机连同一个 Wi-Fi，直接访问手机的 http://<手机 IPv4>:3334；
不要在路由器上做端口转发，不要把手机或这个端口暴露到公网。每个请求都必须带口令
（远手主界面「局域网指令通道」里显示的那串，放在环境变量 YUANHOU_LAN_TOKEN 或 --token），
本脚本不会把完整口令打印出来。

用法：
  export YUANHOU_LAN_TOKEN=<主界面上的口令>
  export YUANHOU_LAN_HOST=<手机 IPv4>          # 可选；不设时先试 mDNS 服务 yuanhou-hand._yuanhou._tcp，再试 yuanhou-hand.local
  python3 yuanhou_lan.py status
  python3 yuanhou_lan.py say 读屏
  python3 yuanhou_lan.py say "点击 搜索" --timeout 90
  python3 yuanhou_lan.py tree
  python3 yuanhou_lan.py screenshot -o shot.png    # 本次运行没在手机上授权截图时会得到 503
退出码：0 成功；1 HTTP/任务没办成；2 参数或连接问题；3 口令不对（401）。
"""

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import urllib.error
import urllib.request

PORT = 3334
NSD_NAME = "yuanhou-hand"
NSD_TYPE = "_yuanhou._tcp"
FALLBACK_HOST = "yuanhou-hand.local"


def mask(tok):
    return "…" + tok[-4:] if tok and len(tok) >= 8 else "（无）"


def discover_dns_sd(timeout=3.0):
    """macOS 自带 dns-sd：解析 yuanhou-hand._yuanhou._tcp，返回 (主机名, 端口) 或 None。"""
    exe = shutil.which("dns-sd")
    if not exe:
        return None
    try:
        p = subprocess.run([exe, "-L", NSD_NAME, NSD_TYPE, "local"], capture_output=True, text=True, timeout=timeout)
        out = p.stdout
    except subprocess.TimeoutExpired as e:   # dns-sd -L 不会自己退出，超时是正常的
        out = e.stdout.decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
    except Exception:
        return None
    m = re.search(r"can be reached at (\S+?)\.?:(\d+)", out)
    if not m:
        return None
    return m.group(1), int(m.group(2))


def resolve_target(args):
    """--host > YUANHOU_LAN_HOST > mDNS 服务发现（dns-sd）> yuanhou-hand.local。"""
    port = args.port
    if args.host:
        return args.host, port
    env = os.environ.get("YUANHOU_LAN_HOST", "").strip()
    if env:
        if ":" in env and env.count(":") == 1:
            h, p = env.split(":")
            return h, int(p)
        return env, port
    found = discover_dns_sd()
    if found:
        return found
    return FALLBACK_HOST, port


def request(args, method, path, body=None, content_type=None, timeout=None):
    token = args.token or os.environ.get("YUANHOU_LAN_TOKEN", "").strip()
    if not token:
        print("缺口令：设置环境变量 YUANHOU_LAN_TOKEN 或用 --token（口令在远手主界面「局域网指令通道」里）", file=sys.stderr)
        sys.exit(2)
    host, port = resolve_target(args)
    url = "http://%s:%d%s" % (host, port, path)
    headers = {"X-Yuanhou-Token": token}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    if args.verbose:
        print("→ %s %s（口令 %s）" % (method, url, mask(token)), file=sys.stderr)
    try:
        with urllib.request.urlopen(req, timeout=timeout or args.connect_timeout) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()
    except (urllib.error.URLError, socket.timeout, OSError) as e:
        reason = getattr(e, "reason", e)
        print("连不上 %s：%s。确认手机和 Mac 在同一个 Wi-Fi、远手前台服务在运行；"
              ".local 解析不了时用 --host 或 YUANHOU_LAN_HOST 填主界面上的 IPv4。" % (url, reason), file=sys.stderr)
        sys.exit(2)


def finish(status, ctype, data, raw_ok=False):
    if status == 401:
        print("401：口令不对或没带（没有返回任何内容）", file=sys.stderr)
        sys.exit(3)
    text = data.decode("utf-8", "replace")
    if "json" in ctype:
        try:
            obj = json.loads(text)
            print(json.dumps(obj, ensure_ascii=False, indent=2))
            if status != 200:
                sys.exit(1)
            if isinstance(obj, dict) and obj.get("ok") is False:
                sys.exit(1)
            return
        except ValueError:
            pass
    sys.stdout.write(text if text.endswith("\n") else text + "\n")
    if status != 200:
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description="远手局域网指令通道客户端（只限家里局域网，需口令）")
    ap.add_argument("--host", help="手机 IPv4 或主机名（默认 YUANHOU_LAN_HOST，其次 mDNS）")
    ap.add_argument("--port", type=int, default=PORT, help="端口，默认 3334")
    ap.add_argument("--token", help="口令（默认读环境变量 YUANHOU_LAN_TOKEN；尽量用环境变量，别留在 shell 历史里）")
    ap.add_argument("--connect-timeout", type=float, default=10.0, help="status/tree/screenshot 的超时秒数")
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="GET /status")
    sp = sub.add_parser("say", help="POST /say：和 adb DEBUG_SAY 同一条路，等这一件事办完")
    sp.add_argument("text", nargs="+", help="要说的话，例如 读屏、打开美团、取消")
    sp.add_argument("--timeout", type=int, default=60, help="手机上等多久（5–120 秒），默认 60")
    sub.add_parser("tree", help="GET /tree：当前界面树（同 DEBUG_TREE）")
    sc = sub.add_parser("screenshot", help="GET /screenshot：PNG；手机上没授权截图时 503")
    sc.add_argument("-o", "--out", default="yuanhou-screen.png")
    args = ap.parse_args()

    if args.cmd == "status":
        finish(*request(args, "GET", "/status"))
    elif args.cmd == "say":
        text = " ".join(args.text)
        t = max(5, min(120, args.timeout))
        body = json.dumps({"text": text, "timeout": t}, ensure_ascii=False).encode("utf-8")
        finish(*request(args, "POST", "/say", body, "application/json; charset=utf-8", timeout=t + 15))
    elif args.cmd == "tree":
        finish(*request(args, "GET", "/tree"))
    elif args.cmd == "screenshot":
        status, ctype, data = request(args, "GET", "/screenshot", timeout=max(args.connect_timeout, 20))
        if status == 200 and ctype.startswith("image/png"):
            with open(args.out, "wb") as f:
                f.write(data)
            print("已保存 %s（%d 字节）" % (args.out, len(data)))
        else:
            finish(status, ctype, data)


if __name__ == "__main__":
    main()

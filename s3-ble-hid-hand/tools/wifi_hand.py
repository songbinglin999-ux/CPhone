#!/usr/bin/env python3
"""S3 Phone Hand —— Wi-Fi TCP 透传客户端（固件 1.1.0+）。

把一条动作包（和 GATT Action 特征完全相同的 [u8 opcode][u8 seq][payload]）通过局域网 TCP 发给 ESP32，
ESP32 再用 BLE HID 在手机上执行，并打印结果。脚本里没有、也不需要任何 Wi-Fi 凭据。

帧格式（小端）：
  请求  [u16 len][opcode][seq][payload...]
  回复  [u16 len][seq][result][opcode][phase][extra...]   phase 0=提交结果，1=执行完的最终结果
  提交结果为 ACCEPTED(8) 时，之后还会再来一帧 phase 1。
  注意：动作执行中 TCP 断开，固件会自动 CANCEL，所以本脚本一定等到最终结果再断开。

用法示例：
  python3 tools/wifi_hand.py --host s3-phone-hand.local status
  python3 tools/wifi_hand.py --host 192.168.0.123 set-screen 1080 2400
  python3 tools/wifi_hand.py --host 192.168.0.123 click 540 1200
  python3 tools/wifi_hand.py type "hello world"
  python3 tools/wifi_hand.py key 0x01 0x19          # Ctrl+V
  python3 tools/wifi_hand.py consumer 0x0224        # Back
  python3 tools/wifi_hand.py raw 2001000000         # 直接发十六进制动作包（seq 用包里的）
主机也可用环境变量 HAND_HOST 指定（默认 s3-phone-hand.local），端口 HAND_PORT（默认 3333）。
"""
import argparse
import os
import random
import socket
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hand_proto as hp  # noqa: E402

OP_TCP_STATUS = 0x40
RES_ACCEPTED = 8


def res_name(r):
    return hp.RESULTS[r] if r < len(hp.RESULTS) else f"?{r}"


def recv_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError("ESP32 关闭了连接")
        b += c
    return b


def recv_frame(s):
    (n,) = struct.unpack("<H", recv_exact(s, 2))
    body = recv_exact(s, n)
    seq, result, op, phase = struct.unpack("<BBBB", body[:4])
    return dict(seq=seq, result=result, op=op, phase=phase, extra=body[4:])


def send_packet(host, port, pkt, timeout=15.0, wait_final=True):
    """发一条动作包，返回 [phase0, (phase1)] 回复列表"""
    seq = pkt[1]
    out = []
    with socket.create_connection((host, port), timeout=5) as s:
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.settimeout(timeout)
        s.sendall(struct.pack("<H", len(pkt)) + pkt)
        while True:
            f = recv_frame(s)
            if f["seq"] != seq:
                print(f"(忽略其它 seq 的回复: {f})", file=sys.stderr)
                continue
            out.append(f)
            if f["phase"] == 1 or f["result"] != RES_ACCEPTED or not wait_final:
                return out


def build(args, seq):
    c = args.cmd
    if c == "status":       return struct.pack("<BB", OP_TCP_STATUS, seq)
    if c == "set-screen":   return hp.set_screen(seq, args.w, args.h, args.cpp, args.max_step)
    if c == "home":         return hp.home_cursor(seq)
    if c == "click":        return hp.click_abs(seq, args.x, args.y, args.hold)
    if c == "longpress":    return hp.longpress_abs(seq, args.x, args.y, args.hold)
    if c == "swipe":        return hp.swipe_abs(seq, args.x1, args.y1, args.x2, args.y2, args.dur)
    if c == "move":         return hp.move_rel(seq, args.dx, args.dy, args.dur)
    if c == "click-here":   return hp.click_here(seq, args.hold)
    if c == "drag":         return hp.drag_rel(seq, args.dx, args.dy, args.dur)
    if c == "wheel":        return hp.wheel(seq, args.v, args.h)
    if c == "type":         return hp.type_text(seq, args.text, args.gap)
    if c == "key":          return hp.key(seq, args.mod, args.usage, args.hold)
    if c == "consumer":     return hp.consumer(seq, args.usage)
    if c == "cancel":       return hp.cancel(seq)
    if c == "raw":
        b = bytes.fromhex(args.hex.replace(" ", ""))
        if len(b) < 2:
            raise SystemExit("raw 包至少要有 opcode 和 seq 两个字节")
        return b
    raise SystemExit(f"未知命令 {c}")


def main():
    num = lambda s: int(s, 0)  # noqa: E731  支持 0x..
    ap = argparse.ArgumentParser(description="S3 Phone Hand Wi-Fi TCP 客户端")
    ap.add_argument("--host", default=os.environ.get("HAND_HOST", "s3-phone-hand.local"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("HAND_PORT", "3333")))
    ap.add_argument("--seq", type=num, default=None, help="0..255，默认随机")
    ap.add_argument("--timeout", type=float, default=15.0, help="等最终结果的秒数")
    sp = ap.add_subparsers(dest="cmd", required=True)
    sp.add_parser("status", help="读 12 字节状态（仅 TCP）")
    p = sp.add_parser("set-screen"); p.add_argument("w", type=num); p.add_argument("h", type=num)
    p.add_argument("--cpp", type=num, default=0, help="counts_per_px_milli，0=1000"); p.add_argument("--max-step", type=num, default=0)
    sp.add_parser("home")
    for n in ("click", "longpress"):
        p = sp.add_parser(n); p.add_argument("x", type=num); p.add_argument("y", type=num); p.add_argument("--hold", type=num, default=0)
    p = sp.add_parser("swipe")
    for a in ("x1", "y1", "x2", "y2"):
        p.add_argument(a, type=num)
    p.add_argument("--dur", type=num, default=0)
    for n in ("move", "drag"):
        p = sp.add_parser(n); p.add_argument("dx", type=num); p.add_argument("dy", type=num); p.add_argument("--dur", type=num, default=0)
    p = sp.add_parser("click-here"); p.add_argument("--hold", type=num, default=0)
    p = sp.add_parser("wheel"); p.add_argument("v", type=num); p.add_argument("h", type=num, nargs="?", default=0)
    p = sp.add_parser("type"); p.add_argument("text"); p.add_argument("--gap", type=num, default=0)
    p = sp.add_parser("key"); p.add_argument("mod", type=num); p.add_argument("usage", type=num); p.add_argument("--hold", type=num, default=0)
    p = sp.add_parser("consumer"); p.add_argument("usage", type=num)
    sp.add_parser("cancel")
    p = sp.add_parser("raw"); p.add_argument("hex")
    args = ap.parse_args()

    seq = args.seq if args.seq is not None else random.randrange(256)
    pkt = build(args, seq & 0xFF)
    print(f"-> {args.host}:{args.port}  {pkt.hex()}")
    try:
        replies = send_packet(args.host, args.port, pkt, args.timeout)
    except (OSError, ConnectionError) as e:
        print(f"连接/收发失败: {e}", file=sys.stderr)
        return 2
    final = None
    for f in replies:
        tag = "submit" if f["phase"] == 0 else "final"
        print(f"<- {tag}: seq={f['seq']} op=0x{f['op']:02x} result={f['result']} ({res_name(f['result'])})")
        if f["op"] == OP_TCP_STATUS and len(f["extra"]) == 12:
            print("   status:", hp.parse_status(f["extra"]))
        final = f
    return 0 if final and final["result"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

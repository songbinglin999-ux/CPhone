#!/usr/bin/env python3
"""S3 Phone Hand GATT 协议打包/解包参考（小端）。仅供手机端实现对照，不连接任何设备。"""
import struct

SVC    = "950f0001-28bb-4f78-8d4f-dae2ba3d0544"
ACTION = "950f0002-28bb-4f78-8d4f-dae2ba3d0544"
STATUS = "950f0003-28bb-4f78-8d4f-dae2ba3d0544"
LED    = "950f0004-28bb-4f78-8d4f-dae2ba3d0544"
BUTTON = "950f0005-28bb-4f78-8d4f-dae2ba3d0544"
INFO   = "950f0006-28bb-4f78-8d4f-dae2ba3d0544"

def click_abs(seq, x, y, hold_ms=0):            return struct.pack("<BBHHH", 0x01, seq, x, y, hold_ms)
def longpress_abs(seq, x, y, hold_ms=0):        return struct.pack("<BBHHH", 0x02, seq, x, y, hold_ms)
def swipe_abs(seq, x1, y1, x2, y2, dur_ms=0):   return struct.pack("<BBHHHHH", 0x03, seq, x1, y1, x2, y2, dur_ms)
def move_rel(seq, dx, dy, dur_ms=0):            return struct.pack("<BBhhH", 0x10, seq, dx, dy, dur_ms)
def click_here(seq, hold_ms=0):                 return struct.pack("<BBH", 0x11, seq, hold_ms)
def longpress_here(seq, hold_ms=0):             return struct.pack("<BBH", 0x12, seq, hold_ms)
def drag_rel(seq, dx, dy, dur_ms=0):            return struct.pack("<BBhhH", 0x13, seq, dx, dy, dur_ms)
def wheel(seq, v, h=0):                         return struct.pack("<BBbb", 0x14, seq, v, h)
def type_text(seq, text, gap_ms=0):
    b = text.encode("ascii")  # 非 ASCII 会在这里报错；固件同样整条拒绝（RES_NON_ASCII=6）
    return struct.pack("<BBH", 0x20, seq, gap_ms) + b
def key(seq, modifiers, usage, hold_ms=0):      return struct.pack("<BBBBH", 0x21, seq, modifiers, usage, hold_ms)
def consumer(seq, usage):                       return struct.pack("<BBH", 0x22, seq, usage)
def set_screen(seq, w, h, counts_per_px_milli=0, max_step=0):
    return struct.pack("<BBHHHH", 0x30, seq, w, h, counts_per_px_milli, max_step)
def home_cursor(seq):                           return struct.pack("<BB", 0x31, seq)
def cancel(seq=0):                              return struct.pack("<BB", 0x7F, seq)

STATES  = ["NOT_READY", "READY", "IN_CALL", "WAITING_CONFIRM", "FAULT"]
RESULTS = ["OK", "BUSY", "BAD_PARAM", "CANCELLED", "NOT_READY", "UNSUPPORTED", "NON_ASCII", "HID_ERROR", "ACCEPTED"]
FLAGS   = ["CONNECTED", "ENCRYPTED", "BONDED", "HID_KBD", "HID_MOUSE", "BUSY", "PAIRING", "DIGITIZER"]

def parse_status(b):
    proto, state, flags, seq, op, res, mtu, hb = struct.unpack("<BBBBBBHI", b)
    return dict(proto=proto, state=STATES[state], flags=[n for i, n in enumerate(FLAGS) if flags >> i & 1],
                last_seq=seq, last_opcode=hex(op), last_result=RESULTS[res], mtu=mtu, heartbeat=hb)

if __name__ == "__main__":
    print(set_screen(1, 720, 1600).hex())
    print(click_abs(2, 360, 800).hex())
    print(swipe_abs(3, 360, 1200, 360, 400, 300).hex())
    print(type_text(4, "hello").hex())
    print(key(5, 0x01, 0x19).hex(), "# Ctrl+V")
    print(consumer(6, 0x0224).hex(), "# AC Back")
    print(parse_status(bytes.fromhex("0101 1f 02 01 00 f700 2a000000".replace(" ", ""))))

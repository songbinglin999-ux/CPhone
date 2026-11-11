package com.yuanhou.hand.hand;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** S3 Phone Hand 自定义 GATT 协议（小端），对照 firmware tools/hand_proto.py。 */
public final class HandProtocol {
    private HandProtocol() {}

    public static final UUID SVC    = UUID.fromString("950f0001-28bb-4f78-8d4f-dae2ba3d0544");
    public static final UUID ACTION = UUID.fromString("950f0002-28bb-4f78-8d4f-dae2ba3d0544");
    public static final UUID STATUS = UUID.fromString("950f0003-28bb-4f78-8d4f-dae2ba3d0544");
    public static final UUID LED    = UUID.fromString("950f0004-28bb-4f78-8d4f-dae2ba3d0544");
    public static final UUID BUTTON = UUID.fromString("950f0005-28bb-4f78-8d4f-dae2ba3d0544");
    public static final UUID INFO   = UUID.fromString("950f0006-28bb-4f78-8d4f-dae2ba3d0544");
    public static final UUID CCCD   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int OP_CLICK_ABS = 0x01;
    public static final int OP_LONGPRESS_ABS = 0x02;
    public static final int OP_SWIPE_ABS = 0x03;
    public static final int OP_MOVE_REL = 0x10;
    public static final int OP_CLICK_HERE = 0x11;
    public static final int OP_LONGPRESS_HERE = 0x12;
    public static final int OP_DRAG_REL = 0x13;
    public static final int OP_WHEEL = 0x14;
    public static final int OP_TYPE_TEXT = 0x20;
    public static final int OP_KEY = 0x21;
    public static final int OP_CONSUMER = 0x22;
    public static final int OP_SET_SCREEN = 0x30;
    public static final int OP_HOME_CURSOR = 0x31;
    public static final int OP_CANCEL = 0x7F;

    public static final int RES_OK = 0;
    public static final int RES_BUSY = 1;
    public static final int RES_BAD_PARAM = 2;
    public static final int RES_CANCELLED = 3;
    public static final int RES_NOT_READY = 4;
    public static final int RES_UNSUPPORTED = 5;
    public static final int RES_NON_ASCII = 6;
    public static final int RES_HID_ERROR = 7;
    public static final int RES_ACCEPTED = 8;

    private static final String[] STATES = {
            "未就绪", "就绪", "通话中", "等待确认", "故障"
    };
    private static final String[] RESULTS = {
            "OK", "BUSY", "BAD_PARAM", "CANCELLED", "NOT_READY",
            "UNSUPPORTED", "NON_ASCII", "HID_ERROR", "ACCEPTED"
    };
    private static final String[] FLAG_NAMES = {
            "已连接", "已加密", "已绑定", "键盘已订阅",
            "鼠标已订阅", "忙", "配对窗口", "Digitizer"
    };

    public static String resultName(int r) {
        if (r >= 0 && r < RESULTS.length) return RESULTS[r];
        return "RES_" + r;
    }

    public static String stateName(int s) {
        if (s >= 0 && s < STATES.length) return STATES[s];
        return "STATE_" + s;
    }

    public static List<String> flagNames(int flags) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < FLAG_NAMES.length; i++) {
            if ((flags & (1 << i)) != 0) out.add(FLAG_NAMES[i]);
        }
        return out;
    }

    public static boolean isFinalResult(int r) {
        return r != RES_ACCEPTED;
    }

    private static ByteBuffer le(int cap) {
        return ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static byte[] clickAbs(int seq, int x, int y, int holdMs) {
        return le(8).put((byte) OP_CLICK_ABS).put((byte) seq)
                .putShort((short) x).putShort((short) y).putShort((short) holdMs).array();
    }

    public static byte[] swipeAbs(int seq, int x1, int y1, int x2, int y2, int durMs) {
        return le(12).put((byte) OP_SWIPE_ABS).put((byte) seq)
                .putShort((short) x1).putShort((short) y1)
                .putShort((short) x2).putShort((short) y2)
                .putShort((short) durMs).array();
    }

    public static byte[] moveRel(int seq, int dx, int dy, int durMs) {
        return le(8).put((byte) OP_MOVE_REL).put((byte) seq)
                .putShort((short) dx).putShort((short) dy).putShort((short) durMs).array();
    }

    public static byte[] clickHere(int seq, int holdMs) {
        return le(4).put((byte) OP_CLICK_HERE).put((byte) seq).putShort((short) holdMs).array();
    }

    public static byte[] typeText(int seq, String text, int gapMs) {
        byte[] body = text.getBytes(StandardCharsets.US_ASCII);
        return le(4 + body.length).put((byte) OP_TYPE_TEXT).put((byte) seq)
                .putShort((short) gapMs).put(body).array();
    }

    public static byte[] key(int seq, int modifiers, int usage, int holdMs) {
        return le(6).put((byte) OP_KEY).put((byte) seq)
                .put((byte) modifiers).put((byte) usage).putShort((short) holdMs).array();
    }

    public static byte[] setScreen(int seq, int w, int h, int countsPerPxMilli, int maxStep) {
        return le(10).put((byte) OP_SET_SCREEN).put((byte) seq)
                .putShort((short) w).putShort((short) h)
                .putShort((short) countsPerPxMilli).putShort((short) maxStep).array();
    }

    public static byte[] homeCursor(int seq) {
        return le(2).put((byte) OP_HOME_CURSOR).put((byte) seq).array();
    }

    public static byte[] longPressAbs(int seq, int x, int y, int holdMs) {
        return le(8).put((byte) OP_LONGPRESS_ABS).put((byte) seq)
                .putShort((short) x).putShort((short) y).putShort((short) holdMs).array();
    }

    public static byte[] wheel(int seq, int vertical, int horizontal) {
        return le(4).put((byte) OP_WHEEL).put((byte) seq)
                .put((byte) vertical).put((byte) horizontal).array();
    }

    /** Consumer 页按键，如 0x0223 AC Home、0x0224 AC Back。 */
    public static byte[] consumer(int seq, int usage) {
        return le(4).put((byte) OP_CONSUMER).put((byte) seq).putShort((short) usage).array();
    }

    public static final int CONSUMER_AC_HOME = 0x0223;
    public static final int CONSUMER_AC_BACK = 0x0224;
    public static final int MOD_LCTRL = 0x01;
    public static final int USAGE_V = 0x19;
    /** 键盘页：回退（Backspace）。清空输入框只用它，不用 Ctrl+A。 */
    public static final int USAGE_BACKSPACE = 0x2A;
    /** 键盘页：End，配 Ctrl 把光标移到整段文末。 */
    public static final int USAGE_END = 0x4D;
    /** 键盘页：右方向键。0.1.5 起不再使用：这台机的百度输入法会吞掉它，清空只用 Ctrl+End + Backspace。 */
    public static final int USAGE_RIGHT = 0x4F;

    public static byte[] cancel(int seq) {
        return le(2).put((byte) OP_CANCEL).put((byte) seq).array();
    }

    public static final class Status {
        public final int proto, state, flags, lastSeq, lastOpcode, lastResult, mtu;
        public final long heartbeat;

        public Status(byte[] b) {
            if (b == null || b.length < 12) {
                throw new IllegalArgumentException("status too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            proto = buf.get() & 0xff;
            state = buf.get() & 0xff;
            flags = buf.get() & 0xff;
            lastSeq = buf.get() & 0xff;
            lastOpcode = buf.get() & 0xff;
            lastResult = buf.get() & 0xff;
            mtu = buf.getShort() & 0xffff;
            heartbeat = buf.getInt() & 0xffffffffL;
        }

        public boolean connected() { return (flags & 0x01) != 0; }
        public boolean encrypted() { return (flags & 0x02) != 0; }
        public boolean bonded() { return (flags & 0x04) != 0; }
        public boolean keyboardSubscribed() { return (flags & 0x08) != 0; }
        public boolean mouseSubscribed() { return (flags & 0x10) != 0; }
        public boolean busy() { return (flags & 0x20) != 0; }

        public boolean hidReady() {
            // bit3 键盘已订阅, bit4 鼠标已订阅
            return (flags & 0x01) != 0 && (flags & 0x02) != 0
                    && (flags & 0x08) != 0 && (flags & 0x10) != 0;
        }

        @Override
        public String toString() {
            return "proto=" + proto
                    + " 状态=" + stateName(state)
                    + " 标志=" + flagNames(flags)
                    + " seq=" + lastSeq
                    + " op=0x" + Integer.toHexString(lastOpcode)
                    + " 结果=" + resultName(lastResult)
                    + " mtu=" + mtu
                    + " hb=" + heartbeat;
        }
    }
}

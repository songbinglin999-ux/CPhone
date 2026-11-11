/*
 * protocol.h —— 「S3 Phone Hand」自定义 GATT 协议（版本 1）。全部多字节字段为小端（little-endian）。
 *
 * 手机 = Central，ESP32 = Peripheral。所有自定义特征都要求加密链路（必须先绑定配对）。
 * 链路上禁止传 API Key、截图、聊天记录；只允许“动作指令”（含要输入到当前焦点框的文字）。
 *
 * 服务 UUID      950f0001-28bb-4f78-8d4f-dae2ba3d0544
 *  Action        950f0002-...  Write / Write Without Response   动作指令（一次一个）
 *  Status        950f0003-...  Read / Notify                    状态 + 心跳（1 s）+ 动作结果
 *  LED           950f0004-...  Read / Write                     设置状态灯模式（1 字节）
 *  Button        950f0005-...  Notify                           按键事件
 *  Info          950f0006-...  Read                             固件信息（ASCII 字符串）
 */
#pragma once
#include <stdint.h>

#define HAND_PROTO_VERSION 1
#define HAND_FW_VERSION    "1.1.0"

/* 128-bit UUID 基址 950fXXXX-28bb-4f78-8d4f-dae2ba3d0544，NimBLE 需要按小端字节序书写 */
#define HAND_UUID128(x16) BLE_UUID128_INIT( \
    0x44, 0x05, 0x3d, 0xba, 0xe2, 0xda, 0x4f, 0x8d, \
    0x78, 0x4f, 0xbb, 0x28, (uint8_t)((x16) & 0xff), (uint8_t)((x16) >> 8), 0x0f, 0x95)

#define HAND_UUID_SVC     0x0001
#define HAND_UUID_ACTION  0x0002
#define HAND_UUID_STATUS  0x0003
#define HAND_UUID_LED     0x0004
#define HAND_UUID_BUTTON  0x0005
#define HAND_UUID_INFO    0x0006

/* ---------- Action 指令：[u8 opcode][u8 seq][payload...] ---------- */
enum hand_opcode {
    /* 绝对坐标（像素，需先 SET_SCREEN）。启用 Digitizer 时走触摸报告，否则走鼠标相对位移换算 */
    OP_CLICK_ABS     = 0x01, /* u16 x, u16 y, u16 hold_ms(0=默认90；允许60..120) */
    OP_LONGPRESS_ABS = 0x02, /* u16 x, u16 y, u16 hold_ms(0=默认750；允许620..900) */
    OP_SWIPE_ABS     = 0x03, /* u16 x1, u16 y1, u16 x2, u16 y2, u16 dur_ms(0=默认300；允许200..450) */

    /* 相对鼠标 */
    OP_MOVE_REL      = 0x10, /* i16 dx, i16 dy, u16 dur_ms(0=按限速自动)  仅移动光标，不按键 */
    OP_CLICK_HERE    = 0x11, /* u16 hold_ms(0=90；60..120)  在当前光标处左键单击 */
    OP_LONGPRESS_HERE= 0x12, /* u16 hold_ms(0=750；620..900) */
    OP_DRAG_REL      = 0x13, /* i16 dx, i16 dy, u16 dur_ms(0=300；200..450) 按住左键直线拖动 */
    OP_WHEEL         = 0x14, /* i8 vertical, i8 horizontal  单份报告的滚轮格数 */

    /* 键盘 */
    OP_TYPE_TEXT     = 0x20, /* u16 inter_key_ms(0=默认50；允许30..80), 其后为文字字节（仅可打印 ASCII 与 \n \t \b） */
    OP_KEY           = 0x21, /* u8 modifiers, u8 usage(键盘页), u16 hold_ms(0=默认20)  单个组合键，如 Ctrl+V */
    OP_CONSUMER      = 0x22, /* u16 usage(Consumer 页，如 0x0223 AC Home, 0x0224 AC Back) */

    /* 设置 */
    OP_SET_SCREEN    = 0x30, /* u16 width_px, u16 height_px, u16 counts_per_px_milli(0=1000), u16 max_counts_per_report(0=默认4) */
    OP_HOME_CURSOR   = 0x31, /* 无参数：把鼠标光标推到左上角 (0,0)，作为绝对→相对换算的起点 */

    /* 仅 Wi-Fi TCP 通道（GATT 上返回 UNSUPPORTED，GATT 直接读 Status 特征即可） */
    OP_TCP_STATUS    = 0x40, /* 无参数：回复附带 12 字节 hand_status_t */

    OP_CANCEL        = 0x7F, /* 无参数：急停，立即中止当前动作并松开所有键/按钮。永不排队、永不返回 BUSY */
};

/* ---------- 结果码（Status.last_result） ---------- */
enum hand_result {
    RES_OK          = 0,
    RES_BUSY        = 1, /* 上一个动作还在执行，本条被丢弃（不排队） */
    RES_BAD_PARAM   = 2, /* 长度不对 / 时长超出允许范围 / 坐标超出屏幕 */
    RES_CANCELLED   = 3, /* 被 CANCEL 或急停键中止 */
    RES_NOT_READY   = 4, /* 未加密，或手机系统尚未订阅对应 HID 报告 */
    RES_UNSUPPORTED = 5, /* 未知 opcode，或需要 SET_SCREEN 却没设 */
    RES_NON_ASCII   = 6, /* TYPE_TEXT 中有 HID 美式键盘打不出的字符（整条拒绝，一个字也不打） */
    RES_HID_ERROR   = 7, /* 发送 HID 报告失败（链路断开等） */
    RES_ACCEPTED    = 8, /* 已接收并开始执行（之后还会再发一次最终结果） */
};

/* ---------- 状态灯 / 套件状态（LED 特征写 1 字节） ---------- */
enum hand_state {
    ST_NOT_READY       = 0, /* 红色慢闪 */
    ST_READY           = 1, /* 绿色常亮 */
    ST_IN_CALL         = 2, /* 蓝色常亮 */
    ST_WAITING_CONFIRM = 3, /* 黄色快闪 */
    ST_FAULT           = 4, /* 红色快闪 */
    ST__COUNT
};

/* ---------- Status 通知（12 字节） ---------- */
#define STF_CONNECTED   (1u << 0)
#define STF_ENCRYPTED   (1u << 1)
#define STF_BONDED      (1u << 2)
#define STF_HID_KBD     (1u << 3) /* 手机已订阅键盘输入报告 */
#define STF_HID_MOUSE   (1u << 4) /* 手机已订阅鼠标（或 Digitizer）输入报告 */
#define STF_BUSY        (1u << 5)
#define STF_PAIRING     (1u << 6) /* 配对窗口打开中 */
#define STF_DIGITIZER   (1u << 7) /* 固件编译时启用了 Digitizer */

typedef struct __attribute__((packed)) {
    uint8_t  proto;        /* = HAND_PROTO_VERSION */
    uint8_t  state;        /* 实际生效的 hand_state（链路不完整时强制 NOT_READY） */
    uint8_t  flags;        /* STF_* */
    uint8_t  last_seq;     /* 最近一条动作的 seq */
    uint8_t  last_opcode;
    uint8_t  last_result;  /* hand_result */
    uint16_t mtu;
    uint32_t heartbeat;    /* 每秒 +1；手机 3 s 收不到即可判探头掉线 */
} hand_status_t;

/* ---------- Button 通知（4 字节） ---------- */
enum hand_button_event {
    BTN_EV_ESTOP        = 1, /* 急停：已本地中止动作，手机应同时取消当前任务 */
    BTN_EV_PAIR_WINDOW  = 2, /* 打开了配对窗口 */
    BTN_EV_RESET_BONDS  = 3, /* 清除绑定并即将重启（这条通常来不及送达） */
};
typedef struct __attribute__((packed)) {
    uint8_t  event;
    uint8_t  gpio;
    uint16_t press_ms;
} hand_button_msg_t;

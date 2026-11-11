#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/* Report ID 分配（HID over GATT 报告描述符） */
#define RID_KEYBOARD  1
#define RID_MOUSE     2
#define RID_CONSUMER  3
#define RID_DIGITIZER 4   /* 仅 CONFIG_HAND_ENABLE_DIGITIZER */

#define DIGI_LOGICAL_MAX 32767

const uint8_t *hid_report_map(size_t *len);

/* 由 ble_hand.c 在 GATT 注册后填写的特征值句柄 */
extern uint16_t g_h_kbd_in, g_h_kbd_out, g_h_mouse_in, g_h_consumer_in, g_h_digi_in;

/* 当前报告值（供 GATT Read 返回） */
extern uint8_t g_kbd_report[8];
extern uint8_t g_mouse_report[7];
extern uint8_t g_consumer_report[2];
extern uint8_t g_digi_report[5];

int  hid_send_keyboard(uint8_t modifiers, uint8_t keycode);
int  hid_send_mouse(uint8_t buttons, int16_t dx, int16_t dy, int8_t wheel, int8_t pan);
int  hid_send_consumer(uint16_t usage);
int  hid_send_touch(bool tip, uint16_t x, uint16_t y);
/* 松开所有键、鼠标按钮、触摸点（急停 / 断开 / 出错时调用；尽力发送，不报错） */
void hid_release_all(void);

/* ASCII → (modifier, usage)，美式键盘布局；不支持返回 false */
bool hid_ascii_to_key(uint8_t c, uint8_t *mod, uint8_t *usage);

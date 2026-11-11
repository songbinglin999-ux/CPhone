/*
 * hid_engine.c —— HID 报告描述符与报告发送（键盘 + 鼠标 + Consumer，可选单点 Digitizer）。
 * 只负责“发一份报告”，不含任何时序；时序规则见 action.c。
 */
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "host/ble_hs.h"
#include "sdkconfig.h"
#include "hid_engine.h"
#include "ble_hand.h"

static const char *TAG = "hid";

uint16_t g_h_kbd_in, g_h_kbd_out, g_h_mouse_in, g_h_consumer_in, g_h_digi_in;
uint8_t g_kbd_report[8];
uint8_t g_mouse_report[7];
uint8_t g_consumer_report[2];
uint8_t g_digi_report[5];

static const uint8_t s_report_map[] = {
    /* ---- Report ID 1：标准键盘（8 字节：修饰键、保留、6 个键码）+ LED 输出报告 ---- */
    0x05, 0x01,       /* Usage Page (Generic Desktop) */
    0x09, 0x06,       /* Usage (Keyboard) */
    0xA1, 0x01,       /* Collection (Application) */
    0x85, RID_KEYBOARD,
    0x05, 0x07,       /*   Usage Page (Keyboard/Keypad) */
    0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
    0x75, 0x01, 0x95, 0x08, 0x81, 0x02,       /*   修饰键 8 bit Input (Data,Var,Abs) */
    0x95, 0x01, 0x75, 0x08, 0x81, 0x01,       /*   保留 1 字节 Input (Const) */
    0x95, 0x05, 0x75, 0x01, 0x05, 0x08,       /*   Usage Page (LEDs) */
    0x19, 0x01, 0x29, 0x05, 0x91, 0x02,       /*   Output 5 bit LED */
    0x95, 0x01, 0x75, 0x03, 0x91, 0x01,       /*   Output 3 bit pad */
    0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
    0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, /* 6 个键码 Input (Data,Array) */
    0xC0,

    /* ---- Report ID 2：相对鼠标（7 字节：按钮、X i16、Y i16、滚轮 i8、横向 i8） ---- */
    0x05, 0x01, 0x09, 0x02,                   /* Usage (Mouse) */
    0xA1, 0x01,
    0x85, RID_MOUSE,
    0x09, 0x01, 0xA1, 0x00,                   /*   Pointer, Collection (Physical) */
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
    0x95, 0x03, 0x75, 0x01, 0x81, 0x02,       /*   3 个按钮 */
    0x95, 0x01, 0x75, 0x05, 0x81, 0x01,       /*   pad */
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31,       /*   X, Y */
    0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F,       /*   Logical -32767..32767 */
    0x75, 0x10, 0x95, 0x02, 0x81, 0x06,       /*   Input (Data,Var,Rel) */
    0x09, 0x38, 0x15, 0x81, 0x25, 0x7F,       /*   Wheel -127..127 */
    0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0x05, 0x0C, 0x0A, 0x38, 0x02,             /*   Consumer AC Pan（横向滚动） */
    0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
    0xC0, 0xC0,

    /* ---- Report ID 3：Consumer Control（2 字节 usage，例如 AC Home 0x223、AC Back 0x224） ---- */
    0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01,
    0x85, RID_CONSUMER,
    0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
    0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
    0xC0,

#if CONFIG_HAND_ENABLE_DIGITIZER
    /* ---- Report ID 4（可选）：单点触摸屏（5 字节：Tip/InRange、X u16、Y u16，0..32767） ---- */
    0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01,       /* Digitizer, Touch Screen, Application */
    0x85, RID_DIGITIZER,
    0x09, 0x22, 0xA1, 0x02,                   /*   Finger, Collection (Logical) */
    0x09, 0x42, 0x09, 0x32,                   /*   Tip Switch, In Range */
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x02, 0x81, 0x02,
    0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
    0x15, 0x00, 0x26, 0xFF, 0x7F, 0x35, 0x00, 0x46, 0xFF, 0x7F,
    0x75, 0x10, 0x95, 0x02, 0x81, 0x02,       /*   Input (Data,Var,Abs) */
    0xC0, 0xC0,
#endif
};

const uint8_t *hid_report_map(size_t *len)
{
    *len = sizeof(s_report_map);
    return s_report_map;
}

static int notify(uint16_t handle, const uint8_t *data, size_t len)
{
    uint16_t conn = ble_hand_conn_handle();
    if (conn == BLE_HS_CONN_HANDLE_NONE || handle == 0) {
        return BLE_HS_ENOTCONN;
    }
    if (!ble_hand_is_subscribed(handle)) {
        return BLE_HS_EAUTHEN; /* 手机系统还没订阅这个报告：HID 尚未就绪 */
    }
    int rc = 0;
    /* mbuf 暂时不够时短暂重试；这是发送可靠性，不是时序策略 */
    for (int attempt = 0; attempt < 25; attempt++) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
        if (!om) {
            rc = BLE_HS_ENOMEM;
        } else {
            rc = ble_gatts_notify_custom(conn, handle, om); /* om 总会被消耗 */
        }
        if (rc != BLE_HS_ENOMEM) {
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }
    if (rc) {
        ESP_LOGW(TAG, "notify handle=%u rc=%d", handle, rc);
    }
    return rc;
}

int hid_send_keyboard(uint8_t modifiers, uint8_t keycode)
{
    memset(g_kbd_report, 0, sizeof(g_kbd_report));
    g_kbd_report[0] = modifiers;
    g_kbd_report[2] = keycode;
    return notify(g_h_kbd_in, g_kbd_report, sizeof(g_kbd_report));
}

int hid_send_mouse(uint8_t buttons, int16_t dx, int16_t dy, int8_t wheel, int8_t pan)
{
    g_mouse_report[0] = buttons & 0x07;
    g_mouse_report[1] = (uint8_t)(dx & 0xff);
    g_mouse_report[2] = (uint8_t)((uint16_t)dx >> 8);
    g_mouse_report[3] = (uint8_t)(dy & 0xff);
    g_mouse_report[4] = (uint8_t)((uint16_t)dy >> 8);
    g_mouse_report[5] = (uint8_t)wheel;
    g_mouse_report[6] = (uint8_t)pan;
    return notify(g_h_mouse_in, g_mouse_report, sizeof(g_mouse_report));
}

int hid_send_consumer(uint16_t usage)
{
    g_consumer_report[0] = usage & 0xff;
    g_consumer_report[1] = usage >> 8;
    return notify(g_h_consumer_in, g_consumer_report, sizeof(g_consumer_report));
}

int hid_send_touch(bool tip, uint16_t x, uint16_t y)
{
#if CONFIG_HAND_ENABLE_DIGITIZER
    if (x > DIGI_LOGICAL_MAX) x = DIGI_LOGICAL_MAX;
    if (y > DIGI_LOGICAL_MAX) y = DIGI_LOGICAL_MAX;
    g_digi_report[0] = tip ? 0x03 : 0x00; /* bit0 Tip Switch, bit1 In Range */
    g_digi_report[1] = x & 0xff;
    g_digi_report[2] = x >> 8;
    g_digi_report[3] = y & 0xff;
    g_digi_report[4] = y >> 8;
    return notify(g_h_digi_in, g_digi_report, sizeof(g_digi_report));
#else
    (void)tip; (void)x; (void)y;
    return BLE_HS_ENOTSUP;
#endif
}

void hid_release_all(void)
{
    if (g_kbd_report[0] || g_kbd_report[2]) hid_send_keyboard(0, 0);
    if (g_mouse_report[0]) hid_send_mouse(0, 0, 0, 0, 0);
    if (g_consumer_report[0] || g_consumer_report[1]) hid_send_consumer(0);
#if CONFIG_HAND_ENABLE_DIGITIZER
    if (g_digi_report[0]) hid_send_touch(false, g_digi_report[1] | (g_digi_report[2] << 8),
                                         g_digi_report[3] | (g_digi_report[4] << 8));
#endif
}

/* 美式键盘：可打印 ASCII 0x20..0x7E + \n \t \b */
bool hid_ascii_to_key(uint8_t c, uint8_t *mod, uint8_t *usage)
{
    const uint8_t SHIFT = 0x02;
    *mod = 0;
    if (c >= 'a' && c <= 'z') { *usage = 0x04 + (c - 'a'); return true; }
    if (c >= 'A' && c <= 'Z') { *usage = 0x04 + (c - 'A'); *mod = SHIFT; return true; }
    if (c >= '1' && c <= '9') { *usage = 0x1E + (c - '1'); return true; }
    switch (c) {
    case '0':  *usage = 0x27; return true;
    case '\n': *usage = 0x28; return true; /* Enter */
    case '\b': *usage = 0x2A; return true; /* Backspace */
    case '\t': *usage = 0x2B; return true;
    case ' ':  *usage = 0x2C; return true;
    case '-':  *usage = 0x2D; return true;
    case '=':  *usage = 0x2E; return true;
    case '[':  *usage = 0x2F; return true;
    case ']':  *usage = 0x30; return true;
    case '\\': *usage = 0x31; return true;
    case ';':  *usage = 0x33; return true;
    case '\'': *usage = 0x34; return true;
    case '`':  *usage = 0x35; return true;
    case ',':  *usage = 0x36; return true;
    case '.':  *usage = 0x37; return true;
    case '/':  *usage = 0x38; return true;
    }
    *mod = SHIFT;
    switch (c) {
    case '!': *usage = 0x1E; return true;
    case '@': *usage = 0x1F; return true;
    case '#': *usage = 0x20; return true;
    case '$': *usage = 0x21; return true;
    case '%': *usage = 0x22; return true;
    case '^': *usage = 0x23; return true;
    case '&': *usage = 0x24; return true;
    case '*': *usage = 0x25; return true;
    case '(': *usage = 0x26; return true;
    case ')': *usage = 0x27; return true;
    case '_': *usage = 0x2D; return true;
    case '+': *usage = 0x2E; return true;
    case '{': *usage = 0x2F; return true;
    case '}': *usage = 0x30; return true;
    case '|': *usage = 0x31; return true;
    case ':': *usage = 0x33; return true;
    case '"': *usage = 0x34; return true;
    case '~': *usage = 0x35; return true;
    case '<': *usage = 0x36; return true;
    case '>': *usage = 0x37; return true;
    case '?': *usage = 0x38; return true;
    }
    *mod = 0;
    return false;
}

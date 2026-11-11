/*
 * ble_hand.c —— NimBLE 外设：GAP 广播/绑定 + HID over GATT（HOGP）+ 自定义「S3 Phone Hand」服务。
 *
 * 绑定策略（不做任何系统限制绕过）：
 *   - 没有任何绑定时：允许第一台手机配对（Just Works，带 Secure Connections）。
 *   - 已有绑定时：只接受已绑定的那台手机；新手机只有在配对窗口打开时（长按 BOOT 3 s）才能配对，
 *     新绑定会替换旧绑定（CONFIG_BT_NIMBLE_MAX_BONDS=1）。
 *   - 连接 30 s 内未完成加密就断开。
 */
#include <string.h>
#include <stdatomic.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "sdkconfig.h"

#include "board.h"
#include "protocol.h"
#include "hid_engine.h"
#include "ble_hand.h"
#include "action.h"
#include "status_led.h"

static const char *TAG = "ble";

void ble_store_config_init(void);

#define APPEARANCE_HID_GENERIC 0x03C0
#define ENCRYPT_TIMEOUT_US     (30 * 1000 * 1000LL)

static uint8_t  s_own_addr_type;
static volatile uint16_t s_conn = BLE_HS_CONN_HANDLE_NONE;
static volatile bool s_encrypted;
static volatile bool s_bonded;
static volatile bool s_pairing_allowed_this_conn;
static volatile int64_t s_pairing_window_until_us;
static uint16_t s_mtu = 23;

static uint8_t s_phone_state = ST_NOT_READY;   /* 手机经 LED 特征设的模式 */
static uint8_t s_last_seq, s_last_op, s_last_res;
static uint32_t s_heartbeat;

/* 订阅状态（CCCD） */
#define MAX_SUBS 8
static struct { uint16_t handle; bool notify; } s_subs[MAX_SUBS];

static uint16_t s_h_status, s_h_button, s_h_led;
static uint8_t  s_protocol_mode = 1; /* 1 = Report Protocol */
static esp_timer_handle_t s_heartbeat_timer, s_enc_timer;

static void start_advertising(void);
static void refresh_led(void);

/* ===================== 工具 ===================== */

uint16_t ble_hand_conn_handle(void) { return s_conn; }
bool ble_hand_is_encrypted(void) { return s_encrypted; }

bool ble_hand_is_subscribed(uint16_t h)
{
    for (int i = 0; i < MAX_SUBS; i++) {
        if (s_subs[i].handle == h) return s_subs[i].notify;
    }
    return false;
}

static void set_subscribed(uint16_t h, bool on)
{
    for (int i = 0; i < MAX_SUBS; i++) {
        if (s_subs[i].handle == h) { s_subs[i].notify = on; return; }
    }
    for (int i = 0; i < MAX_SUBS; i++) {
        if (s_subs[i].handle == 0) { s_subs[i].handle = h; s_subs[i].notify = on; return; }
    }
}

bool ble_hand_hid_ready(void)
{
    return s_conn != BLE_HS_CONN_HANDLE_NONE && s_encrypted &&
           (ble_hand_is_subscribed(g_h_kbd_in) || ble_hand_is_subscribed(g_h_mouse_in) ||
            (g_h_digi_in && ble_hand_is_subscribed(g_h_digi_in)));
}

static bool pairing_window_open(void)
{
    return esp_timer_get_time() < s_pairing_window_until_us;
}

static int bond_count(void)
{
    int n = 0;
    ble_store_util_count(BLE_STORE_OBJ_TYPE_PEER_SEC, &n);
    return n;
}

static uint8_t effective_state(void)
{
    if (!ble_hand_hid_ready()) return ST_NOT_READY;
    return s_phone_state;
}

static void build_status(hand_status_t *st)
{
    uint8_t f = 0;
    if (s_conn != BLE_HS_CONN_HANDLE_NONE) f |= STF_CONNECTED;
    if (s_encrypted) f |= STF_ENCRYPTED;
    if (s_bonded) f |= STF_BONDED;
    if (ble_hand_is_subscribed(g_h_kbd_in)) f |= STF_HID_KBD;
    if (ble_hand_is_subscribed(g_h_mouse_in) || (g_h_digi_in && ble_hand_is_subscribed(g_h_digi_in))) f |= STF_HID_MOUSE;
    if (action_busy()) f |= STF_BUSY;
    if (pairing_window_open()) f |= STF_PAIRING;
#if CONFIG_HAND_ENABLE_DIGITIZER
    f |= STF_DIGITIZER;
#endif
    st->proto = HAND_PROTO_VERSION;
    st->state = effective_state();
    st->flags = f;
    st->last_seq = s_last_seq;
    st->last_opcode = s_last_op;
    st->last_result = s_last_res;
    st->mtu = s_mtu;
    st->heartbeat = s_heartbeat;
}

void ble_hand_get_status(hand_status_t *st) { build_status(st); }

static void notify_status(void)
{
    if (s_conn == BLE_HS_CONN_HANDLE_NONE || !s_encrypted || !ble_hand_is_subscribed(s_h_status)) return;
    hand_status_t st;
    build_status(&st);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&st, sizeof(st));
    if (om) ble_gatts_notify_custom(s_conn, s_h_status, om);
}

void ble_hand_report_result(uint8_t seq, uint8_t opcode, uint8_t result)
{
    s_last_seq = seq;
    s_last_op = opcode;
    s_last_res = result;
    notify_status();
}

void ble_hand_notify_button(uint8_t event, uint8_t gpio, uint16_t press_ms)
{
    if (s_conn == BLE_HS_CONN_HANDLE_NONE || !s_encrypted || !ble_hand_is_subscribed(s_h_button)) return;
    hand_button_msg_t m = { .event = event, .gpio = gpio, .press_ms = press_ms };
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&m, sizeof(m));
    if (om) ble_gatts_notify_custom(s_conn, s_h_button, om);
}

void ble_hand_open_pairing_window(void)
{
    s_pairing_window_until_us = esp_timer_get_time() + (int64_t)CONFIG_HAND_PAIRING_WINDOW_S * 1000000LL;
    ESP_LOGI(TAG, "配对窗口打开 %d s", CONFIG_HAND_PAIRING_WINDOW_S);
    refresh_led();
    if (s_conn == BLE_HS_CONN_HANDLE_NONE) {
        ble_gap_adv_stop();
        start_advertising();
    }
}

void ble_hand_clear_bonds_and_restart(void)
{
    ESP_LOGW(TAG, "清除所有绑定并重启");
    action_cancel();
    if (s_conn != BLE_HS_CONN_HANDLE_NONE) {
        ble_gap_terminate(s_conn, BLE_ERR_REM_USER_CONN_TERM);
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    ble_store_clear();
    vTaskDelay(pdMS_TO_TICKS(100));
    esp_restart();
}

static void refresh_led(void)
{
    status_led_set(effective_state(), pairing_window_open());
}

static void heartbeat_cb(void *arg)
{
    (void)arg;
    s_heartbeat++;
    refresh_led();
    notify_status();
}

static void enc_timeout_cb(void *arg)
{
    (void)arg;
    if (s_conn != BLE_HS_CONN_HANDLE_NONE && !s_encrypted) {
        ESP_LOGW(TAG, "30 s 内未加密，断开");
        ble_gap_terminate(s_conn, BLE_ERR_AUTH_FAIL);
    }
}

/* ===================== GATT 访问回调 ===================== */

static int append(struct os_mbuf *om, const void *d, size_t n)
{
    return os_mbuf_append(om, d, n) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static const uint8_t s_hid_info[4] = { 0x11, 0x01, 0x00, 0x02 }; /* bcdHID 1.11, country 0, NormallyConnectable */
/* PnP ID：VID 来源 0x02(USB-IF)，VID 0x303A(Espressif，芯片原厂)，PID 0x4801，版本 0x0100 */
static const uint8_t s_pnp_id[7] = { 0x02, 0x3A, 0x30, 0x01, 0x48, 0x00, 0x01 };

/* Report Reference 描述符内容：[Report ID, 类型 1=Input 2=Output] */
static const uint8_t s_ref_kbd_in[2]   = { RID_KEYBOARD, 1 };
static const uint8_t s_ref_kbd_out[2]  = { RID_KEYBOARD, 2 };
static const uint8_t s_ref_mouse_in[2] = { RID_MOUSE, 1 };
static const uint8_t s_ref_cons_in[2]  = { RID_CONSUMER, 1 };
#if CONFIG_HAND_ENABLE_DIGITIZER
static const uint8_t s_ref_digi_in[2]  = { RID_DIGITIZER, 1 };
#endif

static int dis_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    uint16_t uuid = ble_uuid_u16(ctxt->chr->uuid);
    if (uuid == 0x2A50) return append(ctxt->om, s_pnp_id, sizeof(s_pnp_id));
    if (uuid == 0x2A29) return append(ctxt->om, "DIY (Espressif ESP32-S3)", 24);
    if (uuid == 0x2A24) return append(ctxt->om, BOARD_NAME, strlen(BOARD_NAME));
    if (uuid == 0x2A26) return append(ctxt->om, HAND_FW_VERSION, strlen(HAND_FW_VERSION));
    return BLE_ATT_ERR_UNLIKELY;
}

static int report_ref_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    return append(ctxt->om, arg, 2);
}

static int hid_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    uint16_t uuid = ble_uuid_u16(ctxt->chr->uuid);
    switch (uuid) {
    case 0x2A4A: /* HID Information */
        return append(ctxt->om, s_hid_info, sizeof(s_hid_info));
    case 0x2A4B: { /* Report Map（长读由 NimBLE 处理 offset） */
        size_t len;
        const uint8_t *map = hid_report_map(&len);
        return append(ctxt->om, map, len);
    }
    case 0x2A4C: /* HID Control Point：Suspend/Exit Suspend，忽略 */
        return 0;
    case 0x2A4E: /* Protocol Mode：只支持 Report 模式，写入忽略但记住 */
        if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) return append(ctxt->om, &s_protocol_mode, 1);
        if (OS_MBUF_PKTLEN(ctxt->om) == 1) os_mbuf_copydata(ctxt->om, 0, 1, &s_protocol_mode);
        return 0;
    case 0x2A4D: /* Report */
        if (attr == g_h_kbd_in)      return append(ctxt->om, g_kbd_report, sizeof(g_kbd_report));
        if (attr == g_h_mouse_in)    return append(ctxt->om, g_mouse_report, sizeof(g_mouse_report));
        if (attr == g_h_consumer_in) return append(ctxt->om, g_consumer_report, sizeof(g_consumer_report));
#if CONFIG_HAND_ENABLE_DIGITIZER
        if (attr == g_h_digi_in)     return append(ctxt->om, g_digi_report, sizeof(g_digi_report));
#endif
        if (attr == g_h_kbd_out) {
            static uint8_t leds;
            if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) return append(ctxt->om, &leds, 1);
            if (OS_MBUF_PKTLEN(ctxt->om) >= 1) os_mbuf_copydata(ctxt->om, 0, 1, &leds);
            return 0;
        }
        return BLE_ATT_ERR_UNLIKELY;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static int hand_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    uint16_t which = (uint16_t)(uintptr_t)arg;
    switch (which) {
    case HAND_UUID_ACTION: {
        uint8_t buf[256];
        uint16_t len = 0;
        if (OS_MBUF_PKTLEN(ctxt->om) > sizeof(buf)) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), &len)) return BLE_ATT_ERR_UNLIKELY;
        uint8_t res = action_submit(buf, len);
        if (len >= 2) ble_hand_report_result(buf[1], buf[0], res);
        return 0;
    }
    case HAND_UUID_STATUS: {
        hand_status_t st;
        build_status(&st);
        return append(ctxt->om, &st, sizeof(st));
    }
    case HAND_UUID_LED:
        if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) return append(ctxt->om, &s_phone_state, 1);
        if (OS_MBUF_PKTLEN(ctxt->om) != 1) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        uint8_t m;
        os_mbuf_copydata(ctxt->om, 0, 1, &m);
        if (m >= ST__COUNT) return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
        s_phone_state = m;
        refresh_led();
        notify_status();
        return 0;
    case HAND_UUID_BUTTON: {
        hand_button_msg_t z = {0};
        return append(ctxt->om, &z, sizeof(z));
    }
    case HAND_UUID_INFO: {
        static const char info[] = "fw=" HAND_FW_VERSION ";proto=1;name=" CONFIG_HAND_DEVICE_NAME
                                   ";board=" BOARD_NAME
#if CONFIG_HAND_ENABLE_DIGITIZER
                                   ";digitizer=1;wifi_tcp=1";
#else
                                   ";digitizer=0;wifi_tcp=1";
#endif
        return append(ctxt->om, info, sizeof(info) - 1);
    }
    }
    return BLE_ATT_ERR_UNLIKELY;
}

/* ===================== GATT 表 ===================== */

static const ble_uuid128_t UUID_SVC    = HAND_UUID128(HAND_UUID_SVC);
static const ble_uuid128_t UUID_ACTION = HAND_UUID128(HAND_UUID_ACTION);
static const ble_uuid128_t UUID_STATUS = HAND_UUID128(HAND_UUID_STATUS);
static const ble_uuid128_t UUID_LED    = HAND_UUID128(HAND_UUID_LED);
static const ble_uuid128_t UUID_BUTTON = HAND_UUID128(HAND_UUID_BUTTON);
static const ble_uuid128_t UUID_INFO   = HAND_UUID128(HAND_UUID_INFO);

#define REPORT_REF_DSC(ref) (struct ble_gatt_dsc_def[]) { { \
        .uuid = BLE_UUID16_DECLARE(0x2908), .att_flags = BLE_ATT_F_READ | BLE_ATT_F_READ_ENC, \
        .access_cb = report_ref_access, .arg = (void *)(ref) }, { 0 } }

static const struct ble_gatt_svc_def s_svcs[] = {
    {   /* Device Information */
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0x180A),
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = BLE_UUID16_DECLARE(0x2A50), .access_cb = dis_access, .flags = BLE_GATT_CHR_F_READ },
            { .uuid = BLE_UUID16_DECLARE(0x2A29), .access_cb = dis_access, .flags = BLE_GATT_CHR_F_READ },
            { .uuid = BLE_UUID16_DECLARE(0x2A24), .access_cb = dis_access, .flags = BLE_GATT_CHR_F_READ },
            { .uuid = BLE_UUID16_DECLARE(0x2A26), .access_cb = dis_access, .flags = BLE_GATT_CHR_F_READ },
            { 0 } },
    },
    {   /* HID Service（HOGP） */
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0x1812),
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = BLE_UUID16_DECLARE(0x2A4A), .access_cb = hid_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC },
            { .uuid = BLE_UUID16_DECLARE(0x2A4B), .access_cb = hid_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC },
            { .uuid = BLE_UUID16_DECLARE(0x2A4C), .access_cb = hid_access,
              .flags = BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE_ENC },
            { .uuid = BLE_UUID16_DECLARE(0x2A4E), .access_cb = hid_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_WRITE_ENC },
            { .uuid = BLE_UUID16_DECLARE(0x2A4D), .access_cb = hid_access, .val_handle = &g_h_kbd_in,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
              .descriptors = REPORT_REF_DSC(s_ref_kbd_in) },
            { .uuid = BLE_UUID16_DECLARE(0x2A4D), .access_cb = hid_access, .val_handle = &g_h_kbd_out,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP |
                       BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_WRITE_ENC,
              .descriptors = REPORT_REF_DSC(s_ref_kbd_out) },
            { .uuid = BLE_UUID16_DECLARE(0x2A4D), .access_cb = hid_access, .val_handle = &g_h_mouse_in,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
              .descriptors = REPORT_REF_DSC(s_ref_mouse_in) },
            { .uuid = BLE_UUID16_DECLARE(0x2A4D), .access_cb = hid_access, .val_handle = &g_h_consumer_in,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
              .descriptors = REPORT_REF_DSC(s_ref_cons_in) },
#if CONFIG_HAND_ENABLE_DIGITIZER
            { .uuid = BLE_UUID16_DECLARE(0x2A4D), .access_cb = hid_access, .val_handle = &g_h_digi_in,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC,
              .descriptors = REPORT_REF_DSC(s_ref_digi_in) },
#endif
            { 0 } },
    },
    {   /* 自定义 S3 Phone Hand 服务：全部要求加密 */
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_SVC.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = &UUID_ACTION.u, .access_cb = hand_access, .arg = (void *)HAND_UUID_ACTION,
              .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE_ENC },
            { .uuid = &UUID_STATUS.u, .access_cb = hand_access, .arg = (void *)HAND_UUID_STATUS,
              .val_handle = &s_h_status,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC },
            { .uuid = &UUID_LED.u, .access_cb = hand_access, .arg = (void *)HAND_UUID_LED,
              .val_handle = &s_h_led,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_WRITE_ENC },
            { .uuid = &UUID_BUTTON.u, .access_cb = hand_access, .arg = (void *)HAND_UUID_BUTTON,
              .val_handle = &s_h_button,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC },
            { .uuid = &UUID_INFO.u, .access_cb = hand_access, .arg = (void *)HAND_UUID_INFO,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC },
            { 0 } },
    },
    { 0 },
};

/* ===================== GAP ===================== */

static void reset_conn_state(void)
{
    s_conn = BLE_HS_CONN_HANDLE_NONE;
    s_encrypted = false;
    s_bonded = false;
    s_mtu = 23;
    memset(s_subs, 0, sizeof(s_subs));
}

static int gap_event(struct ble_gap_event *ev, void *arg)
{
    struct ble_gap_conn_desc desc;
    switch (ev->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (ev->connect.status != 0) {
            start_advertising();
            return 0;
        }
        reset_conn_state();
        s_conn = ev->connect.conn_handle;
        /* 连接时决定：本次连接是否允许“新”配对 */
        s_pairing_allowed_this_conn = (bond_count() == 0) || pairing_window_open();
        ESP_LOGI(TAG, "connected handle=%u pairing_allowed=%d", s_conn, s_pairing_allowed_this_conn);
        esp_timer_start_once(s_enc_timer, ENCRYPT_TIMEOUT_US);
        ble_gap_security_initiate(s_conn); /* 外设发 Security Request，由手机决定加密/配对 */
        {
            struct ble_gap_upd_params p = {
                .itvl_min = 6, .itvl_max = 12,   /* 7.5–15 ms，让 HID 报告节拍稳定 */
                .latency = 0, .supervision_timeout = 400,
                .min_ce_len = 0, .max_ce_len = 0,
            };
            ble_gap_update_params(s_conn, &p);
        }
        refresh_led();
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected reason=0x%x", ev->disconnect.reason);
        action_cancel();
        esp_timer_stop(s_enc_timer);
        reset_conn_state();
        refresh_led();
        start_advertising();
        return 0;

    case BLE_GAP_EVENT_ENC_CHANGE:
        if (ble_gap_conn_find(ev->enc_change.conn_handle, &desc) != 0) return 0;
        if (ev->enc_change.status != 0) {
            ESP_LOGW(TAG, "加密失败 status=%d", ev->enc_change.status);
            return 0;
        }
        {
            struct ble_store_key_sec key = { 0 };
            struct ble_store_value_sec val;
            key.peer_addr = desc.peer_id_addr;
            bool known = ble_store_read_peer_sec(&key, &val) == 0;
            if (!known && !s_pairing_allowed_this_conn) {
                ESP_LOGW(TAG, "非已绑定手机且配对窗口未开，断开");
                ble_gap_terminate(ev->enc_change.conn_handle, BLE_ERR_AUTH_FAIL);
                return 0;
            }
            s_encrypted = desc.sec_state.encrypted;
            s_bonded = desc.sec_state.bonded;
            esp_timer_stop(s_enc_timer);
            if (s_bonded) s_pairing_window_until_us = 0; /* 配对完成即关窗 */
            ESP_LOGI(TAG, "加密完成 bonded=%d", s_bonded);
        }
        refresh_led();
        notify_status();
        return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
        /* 已绑定的手机要求重新配对（例如手机侧删了配对）。只在配对窗口内允许 */
        if (!pairing_window_open()) {
            ESP_LOGW(TAG, "重复配对被拒绝：请先长按 BOOT 3 s 打开配对窗口");
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        }
        if (ble_gap_conn_find(ev->repeat_pairing.conn_handle, &desc) == 0) {
            ble_store_util_delete_peer(&desc.peer_id_addr);
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;

    case BLE_GAP_EVENT_SUBSCRIBE:
        set_subscribed(ev->subscribe.attr_handle, ev->subscribe.cur_notify || ev->subscribe.cur_indicate);
        ESP_LOGI(TAG, "subscribe handle=%u notify=%d", ev->subscribe.attr_handle, ev->subscribe.cur_notify);
        refresh_led();
        return 0;

    case BLE_GAP_EVENT_MTU:
        s_mtu = ev->mtu.value;
        return 0;

    case BLE_GAP_EVENT_PASSKEY_ACTION:
        /* IO 能力为 NoInputNoOutput → Just Works，不应出现；保险起见拒绝其它方式 */
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        if (s_conn == BLE_HS_CONN_HANDLE_NONE) start_advertising();
        return 0;
    }
    return 0;
}

static void start_advertising(void)
{
    struct ble_hs_adv_fields f = { 0 };
    const char *name = ble_svc_gap_device_name();
    f.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    f.appearance = APPEARANCE_HID_GENERIC;
    f.appearance_is_present = 1;
    f.uuids16 = (ble_uuid16_t[]) { BLE_UUID16_INIT(0x1812) };
    f.num_uuids16 = 1;
    f.uuids16_is_complete = 0;
    f.name = (uint8_t *)name;
    f.name_len = strlen(name);
    f.name_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&f);
    if (rc) { ESP_LOGE(TAG, "adv_set_fields rc=%d", rc); return; }

    struct ble_hs_adv_fields sr = { 0 };
    sr.uuids128 = (ble_uuid128_t *)&UUID_SVC;
    sr.num_uuids128 = 1;
    sr.uuids128_is_complete = 0;
    rc = ble_gap_adv_rsp_set_fields(&sr);
    if (rc) { ESP_LOGE(TAG, "adv_rsp_set_fields rc=%d", rc); }

    struct ble_gap_adv_params p = { 0 };
    p.conn_mode = BLE_GAP_CONN_MODE_UND;
    p.disc_mode = BLE_GAP_DISC_MODE_GEN;
    p.itvl_min = BLE_GAP_ADV_ITVL_MS(30);
    p.itvl_max = BLE_GAP_ADV_ITVL_MS(60);
    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
    if (rc && rc != BLE_HS_EALREADY) ESP_LOGE(TAG, "adv_start rc=%d", rc);
    else ESP_LOGI(TAG, "advertising as \"%s\"", name);
}

/* 存储满（已有 1 个绑定，又来新手机）：只在配对窗口内才淘汰旧绑定，否则拒绝写入 */
static int store_status_cb(struct ble_store_status_event *event, void *arg)
{
    if (event->event_code == BLE_STORE_EVENT_OVERFLOW || event->event_code == BLE_STORE_EVENT_FULL) {
        if (!pairing_window_open() && bond_count() > 0) {
            ESP_LOGW(TAG, "绑定已满且配对窗口未开，拒绝新绑定");
            return BLE_HS_ESTORE_CAP;
        }
    }
    return ble_store_util_status_rr(event, arg);
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    assert(rc == 0);
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    assert(rc == 0);
    ESP_LOGI(TAG, "bonds=%d", bond_count());
    start_advertising();
}

static void on_reset(int reason)
{
    ESP_LOGE(TAG, "host reset reason=%d", reason);
}

static void host_task(void *param)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void ble_hand_init(void)
{
    ESP_ERROR_CHECK(nimble_port_init());

    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.gatts_register_cb = NULL;
    ble_hs_cfg.store_status_cb = store_status_cb;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set(CONFIG_HAND_DEVICE_NAME);
    ble_svc_gap_device_appearance_set(APPEARANCE_HID_GENERIC);

    int rc = ble_gatts_count_cfg(s_svcs);
    ESP_ERROR_CHECK(rc == 0 ? ESP_OK : ESP_FAIL);
    rc = ble_gatts_add_svcs(s_svcs);
    ESP_ERROR_CHECK(rc == 0 ? ESP_OK : ESP_FAIL);

    ble_store_config_init();

    const esp_timer_create_args_t hb = { .callback = heartbeat_cb, .name = "hb" };
    ESP_ERROR_CHECK(esp_timer_create(&hb, &s_heartbeat_timer));
    ESP_ERROR_CHECK(esp_timer_start_periodic(s_heartbeat_timer, 1000 * 1000));
    const esp_timer_create_args_t et = { .callback = enc_timeout_cb, .name = "enc_to" };
    ESP_ERROR_CHECK(esp_timer_create(&et, &s_enc_timer));

    nimble_port_freertos_init(host_task);
}

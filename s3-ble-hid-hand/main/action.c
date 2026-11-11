/*
 * action.c —— 动作执行器：把一条 Action 指令变成一串 HID 报告。
 *
 * ======================= 时序规则（务必保持） =======================
 * 允许（只为“系统认得出 / 输入法不丢字”，全部为固定默认值或手机给定值，固件不引入随机）：
 *   - 普通点击：按下保持 60–120 ms（默认 90 ms）。太短个别界面不认点击，>500 ms 会变长按。
 *   - 长按：620–900 ms（默认 750 ms）。Android 长按阈值约 500 ms，留足 BLE 调度抖动余量。
 *   - 滑动：200–450 ms（默认 300 ms），直线、最短路径、匀速分段；时长只为让系统认成滑动而不是瞬移。
 *   - 逐键间隔：30–80 ms（默认 50 ms），防止输入法合成丢字。
 * 禁止（反检测 / 伪装真人，本固件一律不实现，也不要日后加上）：
 *   - 落点随机偏移（如 ±3–8 px）——始终命中指令给出的精确坐标；
 *   - 无规律抖动、微震动；
 *   - 曲线/弧线轨迹、为“像真人”设计的加减速；
 *   - “想事情”式停顿；
 *   - 按应用类型改变时序；
 *   - 词与词之间的拟人停顿。
 * 应用提示环境异常时由手机端立即停止任务；固件不会因此换一种输入方式或改时序再试。
 * ====================================================================
 */
#include <string.h>
#include <stdlib.h>
#include <stdatomic.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "sdkconfig.h"

#include "protocol.h"
#include "hid_engine.h"
#include "ble_hand.h"
#include "action.h"
#include "wifi_hand.h"

static const char *TAG = "action";

/* ---- 允许的时序范围（见文件头） ---- */
#define CLICK_HOLD_MIN_MS     60
#define CLICK_HOLD_MAX_MS     120
#define CLICK_HOLD_DEF_MS     90
#define LONG_HOLD_MIN_MS      620
#define LONG_HOLD_MAX_MS      900
#define LONG_HOLD_DEF_MS      750
#define SWIPE_MIN_MS          200
#define SWIPE_MAX_MS          450
#define SWIPE_DEF_MS          300
#define KEY_GAP_MIN_MS        30
#define KEY_GAP_MAX_MS        80
#define KEY_GAP_DEF_MS        50
#define KEY_DOWN_MS           20   /* 每个键按下到松开的固定时长 */
#define KEY_HOLD_MAX_MS       300  /* OP_KEY 上限，避免触发系统自动重复 */
#define MOVE_MAX_MS           5000
#define REPORT_INTERVAL_MS    10   /* 移动分段之间的固定节拍 */
#define SETTLE_MS             20   /* 光标到位后、按下前的固定等待，让系统先处理完移动 */

/* 鼠标光标回原点：向左上推若干份大位移报告 */
#define HOME_REPORTS          25
#define HOME_STEP             (-2000)

typedef struct {
    uint8_t op, seq, src;
    uint32_t tag;
    uint8_t payload[250];
    uint16_t plen;
} action_t;

static QueueHandle_t s_q;
static SemaphoreHandle_t s_submit_mtx;  /* GATT（NimBLE host 任务）与 TCP 任务都会调用 action_submit */
static atomic_bool s_cancel;
static atomic_bool s_busy;

/* 屏幕与绝对→相对换算参数（SET_SCREEN） */
static uint16_t s_scr_w, s_scr_h;
static uint32_t s_cpp_milli = 1000;  /* 每像素对应的鼠标计数 ×1000 */
static uint16_t s_max_step = 4;      /* 定位移动时每份报告最大计数（低速可避开系统指针加速） */
/* 估计的光标位置（像素） */
static bool s_cur_known;
static int32_t s_cur_x, s_cur_y;

static inline uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static inline int16_t  rds16(const uint8_t *p) { return (int16_t)rd16(p); }

bool action_busy(void) { return atomic_load(&s_busy); }

void action_cancel(void)
{
    atomic_store(&s_cancel, true);
    if (!atomic_load(&s_busy)) {
        /* 没有动作在跑：直接确保全部松开 */
        hid_release_all();
        atomic_store(&s_cancel, false);
    }
}

/* 可被急停打断的等待；被打断返回 false */
static bool wait_ms(uint32_t ms)
{
    while (ms > 0) {
        if (atomic_load(&s_cancel)) return false;
        uint32_t s = ms > 5 ? 5 : ms;
        vTaskDelay(pdMS_TO_TICKS(s));
        ms -= s;
    }
    return !atomic_load(&s_cancel);
}

#define CHECK_HID(expr) do { if ((expr) != 0) return RES_HID_ERROR; } while (0)
#define WAIT(ms)        do { if (!wait_ms(ms)) return RES_CANCELLED; } while (0)

/*
 * 直线移动 (dx, dy) 个鼠标计数。
 * 匀速分成 n 段，每段的位移按整数插值累积（保证总和精确等于 dx/dy），节拍固定，无抖动无曲线。
 *  limit_speed=true：定位用，按 s_max_step 限速（避开系统指针加速，使换算更准）；
 *  dur_ms>0：在该时长内走完（滑动/拖动用）。
 */
static uint8_t move_straight(int32_t dx, int32_t dy, uint32_t dur_ms, uint8_t buttons, bool limit_speed)
{
    int32_t adx = abs(dx), ady = abs(dy);
    int32_t span = adx > ady ? adx : ady;
    if (span == 0) return RES_OK;
    uint32_t n = 1;
    if (dur_ms > 0) n = dur_ms / REPORT_INTERVAL_MS;
    if (limit_speed && s_max_step > 0) {
        uint32_t ns = (span + s_max_step - 1) / s_max_step;
        if (ns > n) n = ns;
    }
    uint32_t n_cap = (span + 32766) / 32767;  /* 单份报告不超过 int16 */
    if (n < n_cap) n = n_cap;
    if (n < 1) n = 1;
    uint32_t step_ms = (dur_ms > 0 && !limit_speed) ? dur_ms / n : REPORT_INTERVAL_MS;
    if (step_ms < 1) step_ms = 1;

    int32_t sent_x = 0, sent_y = 0;
    for (uint32_t i = 1; i <= n; i++) {
        int32_t tx = (int32_t)((int64_t)dx * i / n);
        int32_t ty = (int32_t)((int64_t)dy * i / n);
        CHECK_HID(hid_send_mouse(buttons, (int16_t)(tx - sent_x), (int16_t)(ty - sent_y), 0, 0));
        sent_x = tx;
        sent_y = ty;
        if (i < n) WAIT(step_ms);
    }
    return RES_OK;
}

static uint8_t home_cursor(void)
{
    for (int i = 0; i < HOME_REPORTS; i++) {
        CHECK_HID(hid_send_mouse(0, HOME_STEP, HOME_STEP, 0, 0));
        WAIT(REPORT_INTERVAL_MS);
    }
    s_cur_known = true;
    s_cur_x = 0;
    s_cur_y = 0;
    return RES_OK;
}

static int32_t px_to_counts(int32_t px)
{
    int64_t v = (int64_t)px * s_cpp_milli;
    return (int32_t)(v >= 0 ? (v + 500) / 1000 : (v - 500) / 1000);
}

/* 鼠标模式：把光标直线移到像素 (x, y) */
static __attribute__((unused)) uint8_t mouse_move_to(uint16_t x, uint16_t y)
{
    uint8_t r;
    if (!s_cur_known && (r = home_cursor()) != RES_OK) return r;
    r = move_straight(px_to_counts((int32_t)x - s_cur_x), px_to_counts((int32_t)y - s_cur_y), 0, 0, true);
    if (r != RES_OK) { s_cur_known = false; return r; }
    s_cur_x = x;
    s_cur_y = y;
    return RES_OK;
}

static uint8_t press_here(uint16_t hold_ms)
{
    CHECK_HID(hid_send_mouse(0x01, 0, 0, 0, 0));
    WAIT(hold_ms);
    CHECK_HID(hid_send_mouse(0x00, 0, 0, 0, 0));
    return RES_OK;
}

#if CONFIG_HAND_ENABLE_DIGITIZER
static uint16_t to_digi(uint16_t v, uint16_t size)
{
    if (size <= 1) return 0;
    return (uint16_t)(((uint32_t)v * DIGI_LOGICAL_MAX) / (size - 1));
}
#endif

static uint8_t do_press_abs(uint16_t x, uint16_t y, uint16_t hold_ms)
{
#if CONFIG_HAND_ENABLE_DIGITIZER
    uint16_t dx = to_digi(x, s_scr_w), dy = to_digi(y, s_scr_h);
    CHECK_HID(hid_send_touch(true, dx, dy));
    WAIT(hold_ms);
    CHECK_HID(hid_send_touch(false, dx, dy));
    return RES_OK;
#else
    uint8_t r = mouse_move_to(x, y);
    if (r != RES_OK) return r;
    WAIT(SETTLE_MS);
    return press_here(hold_ms);
#endif
}

static uint8_t do_swipe_abs(uint16_t x1, uint16_t y1, uint16_t x2, uint16_t y2, uint16_t dur)
{
#if CONFIG_HAND_ENABLE_DIGITIZER
    /* 触摸：按下起点 → 匀速直线分段 → 终点抬起 */
    uint32_t n = dur / REPORT_INTERVAL_MS;
    if (n < 1) n = 1;
    CHECK_HID(hid_send_touch(true, to_digi(x1, s_scr_w), to_digi(y1, s_scr_h)));
    for (uint32_t i = 1; i <= n; i++) {
        WAIT(dur / n);
        int32_t x = x1 + ((int32_t)x2 - x1) * (int32_t)i / (int32_t)n;
        int32_t y = y1 + ((int32_t)y2 - y1) * (int32_t)i / (int32_t)n;
        CHECK_HID(hid_send_touch(true, to_digi(x, s_scr_w), to_digi(y, s_scr_h)));
    }
    CHECK_HID(hid_send_touch(false, to_digi(x2, s_scr_w), to_digi(y2, s_scr_h)));
    return RES_OK;
#else
    /* 鼠标：先低速定位到起点，按住左键，在 dur 内直线拖到终点，松开 */
    uint8_t r = mouse_move_to(x1, y1);
    if (r != RES_OK) return r;
    WAIT(SETTLE_MS);
    CHECK_HID(hid_send_mouse(0x01, 0, 0, 0, 0));
    r = move_straight(px_to_counts((int32_t)x2 - x1), px_to_counts((int32_t)y2 - y1), dur, 0x01, false);
    if (r != RES_OK) { s_cur_known = false; return r; }
    CHECK_HID(hid_send_mouse(0x00, 0, 0, 0, 0));
    /* 快速拖动可能触发系统指针加速，位置估计不再可靠：下次绝对定位前先回原点 */
    s_cur_known = false;
    return RES_OK;
#endif
}

static uint8_t do_type(const uint8_t *s, size_t n, uint16_t gap)
{
    for (size_t i = 0; i < n; i++) {
        uint8_t mod, usage;
        hid_ascii_to_key(s[i], &mod, &usage);
        CHECK_HID(hid_send_keyboard(mod, usage));
        WAIT(KEY_DOWN_MS);
        CHECK_HID(hid_send_keyboard(0, 0));
        if (i + 1 < n) WAIT(gap); /* 固定逐键间隔；词与词之间不额外停顿 */
    }
    return RES_OK;
}

/* ---------- 校验（在 GATT 回调里同步完成，不合法的指令不会进入执行） ---------- */

static bool in_range(uint16_t v, uint16_t lo, uint16_t hi) { return v >= lo && v <= hi; }

static uint8_t pick(uint16_t v, uint16_t def, uint16_t lo, uint16_t hi, uint16_t *out)
{
    if (v == 0) { *out = def; return RES_OK; }
    if (!in_range(v, lo, hi)) return RES_BAD_PARAM;
    *out = v;
    return RES_OK;
}

static bool xy_ok(uint16_t x, uint16_t y) { return x < s_scr_w && y < s_scr_h; }

static uint8_t validate(const action_t *a)
{
    const uint8_t *p = a->payload;
    uint16_t t;
    switch (a->op) {
    case OP_CLICK_ABS:
    case OP_LONGPRESS_ABS:
        if (a->plen != 6) return RES_BAD_PARAM;
        if (s_scr_w == 0) return RES_UNSUPPORTED;
        if (!xy_ok(rd16(p), rd16(p + 2))) return RES_BAD_PARAM;
        return a->op == OP_CLICK_ABS ? pick(rd16(p + 4), CLICK_HOLD_DEF_MS, CLICK_HOLD_MIN_MS, CLICK_HOLD_MAX_MS, &t)
                                     : pick(rd16(p + 4), LONG_HOLD_DEF_MS, LONG_HOLD_MIN_MS, LONG_HOLD_MAX_MS, &t);
    case OP_SWIPE_ABS:
        if (a->plen != 10) return RES_BAD_PARAM;
        if (s_scr_w == 0) return RES_UNSUPPORTED;
        if (!xy_ok(rd16(p), rd16(p + 2)) || !xy_ok(rd16(p + 4), rd16(p + 6))) return RES_BAD_PARAM;
        return pick(rd16(p + 8), SWIPE_DEF_MS, SWIPE_MIN_MS, SWIPE_MAX_MS, &t);
    case OP_MOVE_REL:
        if (a->plen != 6) return RES_BAD_PARAM;
        return rd16(p + 4) <= MOVE_MAX_MS ? RES_OK : RES_BAD_PARAM;
    case OP_CLICK_HERE:
        if (a->plen != 2) return RES_BAD_PARAM;
        return pick(rd16(p), CLICK_HOLD_DEF_MS, CLICK_HOLD_MIN_MS, CLICK_HOLD_MAX_MS, &t);
    case OP_LONGPRESS_HERE:
        if (a->plen != 2) return RES_BAD_PARAM;
        return pick(rd16(p), LONG_HOLD_DEF_MS, LONG_HOLD_MIN_MS, LONG_HOLD_MAX_MS, &t);
    case OP_DRAG_REL:
        if (a->plen != 6) return RES_BAD_PARAM;
        return pick(rd16(p + 4), SWIPE_DEF_MS, SWIPE_MIN_MS, SWIPE_MAX_MS, &t);
    case OP_WHEEL:
        return a->plen == 2 ? RES_OK : RES_BAD_PARAM;
    case OP_TYPE_TEXT:
        if (a->plen < 3) return RES_BAD_PARAM;
        if (pick(rd16(p), KEY_GAP_DEF_MS, KEY_GAP_MIN_MS, KEY_GAP_MAX_MS, &t) != RES_OK) return RES_BAD_PARAM;
        for (uint16_t i = 2; i < a->plen; i++) {
            uint8_t m, u;
            if (!hid_ascii_to_key(p[i], &m, &u)) return RES_NON_ASCII; /* 整条拒绝 */
        }
        return RES_OK;
    case OP_KEY:
        if (a->plen != 4) return RES_BAD_PARAM;
        return pick(rd16(p + 2), KEY_DOWN_MS, 10, KEY_HOLD_MAX_MS, &t);
    case OP_CONSUMER:
        return a->plen == 2 ? RES_OK : RES_BAD_PARAM;
    case OP_HOME_CURSOR:
        return a->plen == 0 ? RES_OK : RES_BAD_PARAM;
    }
    return RES_UNSUPPORTED;
}

static uint8_t execute(const action_t *a)
{
    const uint8_t *p = a->payload;
    uint16_t t = 0;
    switch (a->op) {
    case OP_CLICK_ABS:
        pick(rd16(p + 4), CLICK_HOLD_DEF_MS, CLICK_HOLD_MIN_MS, CLICK_HOLD_MAX_MS, &t);
        return do_press_abs(rd16(p), rd16(p + 2), t);
    case OP_LONGPRESS_ABS:
        pick(rd16(p + 4), LONG_HOLD_DEF_MS, LONG_HOLD_MIN_MS, LONG_HOLD_MAX_MS, &t);
        return do_press_abs(rd16(p), rd16(p + 2), t);
    case OP_SWIPE_ABS:
        pick(rd16(p + 8), SWIPE_DEF_MS, SWIPE_MIN_MS, SWIPE_MAX_MS, &t);
        return do_swipe_abs(rd16(p), rd16(p + 2), rd16(p + 4), rd16(p + 6), t);
    case OP_MOVE_REL: {
        uint8_t r = move_straight(rds16(p), rds16(p + 2), rd16(p + 4), 0, rd16(p + 4) == 0);
        s_cur_known = false; /* 相对移动后不再假设知道像素位置 */
        return r;
    }
    case OP_CLICK_HERE:
        pick(rd16(p), CLICK_HOLD_DEF_MS, CLICK_HOLD_MIN_MS, CLICK_HOLD_MAX_MS, &t);
        return press_here(t);
    case OP_LONGPRESS_HERE:
        pick(rd16(p), LONG_HOLD_DEF_MS, LONG_HOLD_MIN_MS, LONG_HOLD_MAX_MS, &t);
        return press_here(t);
    case OP_DRAG_REL: {
        pick(rd16(p + 4), SWIPE_DEF_MS, SWIPE_MIN_MS, SWIPE_MAX_MS, &t);
        CHECK_HID(hid_send_mouse(0x01, 0, 0, 0, 0));
        uint8_t r = move_straight(rds16(p), rds16(p + 2), t, 0x01, false);
        s_cur_known = false;
        if (r != RES_OK) return r;
        CHECK_HID(hid_send_mouse(0x00, 0, 0, 0, 0));
        return RES_OK;
    }
    case OP_WHEEL:
        CHECK_HID(hid_send_mouse(0, 0, 0, (int8_t)p[0], (int8_t)p[1]));
        return RES_OK;
    case OP_TYPE_TEXT:
        pick(rd16(p), KEY_GAP_DEF_MS, KEY_GAP_MIN_MS, KEY_GAP_MAX_MS, &t);
        return do_type(p + 2, a->plen - 2, t);
    case OP_KEY:
        pick(rd16(p + 2), KEY_DOWN_MS, 10, KEY_HOLD_MAX_MS, &t);
        CHECK_HID(hid_send_keyboard(p[0], p[1]));
        WAIT(t);
        CHECK_HID(hid_send_keyboard(0, 0));
        return RES_OK;
    case OP_CONSUMER:
        CHECK_HID(hid_send_consumer(rd16(p)));
        WAIT(KEY_DOWN_MS);
        CHECK_HID(hid_send_consumer(0));
        return RES_OK;
    case OP_HOME_CURSOR:
        return home_cursor();
    }
    return RES_UNSUPPORTED;
}

static uint8_t submit_locked(const uint8_t *data, size_t len, uint8_t src, uint32_t tag)
{
    if (len < 2) return RES_BAD_PARAM;
    uint8_t op = data[0];

    if (op == OP_CANCEL) {           /* 急停永远立即生效 */
        action_cancel();
        return RES_OK;
    }
    if (op == OP_SET_SCREEN) {       /* 设置类指令同步完成 */
        if (len != 2 + 8) return RES_BAD_PARAM;
        if (action_busy()) return RES_BUSY;
        uint16_t w = rd16(data + 2), h = rd16(data + 4), cpm = rd16(data + 6), ms = rd16(data + 8);
        if (w == 0 || h == 0) return RES_BAD_PARAM;
        s_scr_w = w;
        s_scr_h = h;
        s_cpp_milli = cpm ? cpm : 1000;
        s_max_step = ms ? ms : 4;
        s_cur_known = false;
        ESP_LOGI(TAG, "screen %ux%u cpp=%lu/1000 max_step=%u", w, h, (unsigned long)s_cpp_milli, s_max_step);
        return RES_OK;
    }
    if (len - 2 > sizeof(((action_t *)0)->payload)) return RES_BAD_PARAM;

    static action_t a;   /* 由 s_submit_mtx 保护，单实例即可；入队时复制 */
    a.op = op;
    a.src = src;
    a.tag = tag;
    a.seq = data[1];
    a.plen = len - 2;
    memcpy(a.payload, data + 2, a.plen);

    uint8_t v = validate(&a);
    if (v != RES_OK) return v;
    if (!ble_hand_hid_ready()) return RES_NOT_READY;
    if (action_busy()) return RES_BUSY;  /* 不排队：手机等上一条结果再发下一条 */

    atomic_store(&s_cancel, false);
    atomic_store(&s_busy, true);
    if (xQueueSend(s_q, &a, 0) != pdTRUE) {
        atomic_store(&s_busy, false);
        return RES_BUSY;
    }
    return RES_ACCEPTED;
}

uint8_t action_submit_from(const uint8_t *data, size_t len, uint8_t src, uint32_t tag)
{
    if (len >= 2 && data[0] == OP_CANCEL) {   /* 急停不等锁 */
        action_cancel();
        return RES_OK;
    }
    xSemaphoreTake(s_submit_mtx, portMAX_DELAY);
    uint8_t r = submit_locked(data, len, src, tag);
    xSemaphoreGive(s_submit_mtx);
    return r;
}

uint8_t action_submit(const uint8_t *data, size_t len)
{
    return action_submit_from(data, len, ACTION_SRC_GATT, 0);
}

static void action_task(void *arg)
{
    action_t a;
    for (;;) {
        if (xQueueReceive(s_q, &a, portMAX_DELAY) != pdTRUE) continue;
        uint8_t r = execute(&a);
        if (r != RES_OK) {
            hid_release_all();   /* 被取消或出错：保证没有键/按钮停在按下状态 */
            if (r == RES_CANCELLED) s_cur_known = false;
        }
        atomic_store(&s_cancel, false);
        atomic_store(&s_busy, false);
        ESP_LOGI(TAG, "op=0x%02x seq=%u result=%u", a.op, a.seq, r);
        ble_hand_report_result(a.seq, a.op, r);
        if (a.src == ACTION_SRC_TCP) wifi_hand_report_final(a.seq, a.op, r, a.tag);
    }
}

void action_init(void)
{
    s_submit_mtx = xSemaphoreCreateMutex();
    s_q = xQueueCreate(1, sizeof(action_t));
    xTaskCreatePinnedToCore(action_task, "action", 4096, NULL, 5, NULL, 1);
}

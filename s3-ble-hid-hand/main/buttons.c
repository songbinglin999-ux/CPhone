/*
 * buttons.c —— 本地按键（在家用：急停、开配对窗口、清绑定）。人在外面时取消仍靠电话里说“取消”。
 *
 * 板载 BOOT（GPIO0，低电平有效）一键多用，按松开时的按住时长判定：
 *   < 1 s          → 急停：立即中止当前动作并松开所有键；通知手机 BTN_EV_ESTOP
 *   3 s – 8 s      → 打开配对窗口（默认 60 s，紫灯闪），允许新手机配对并替换旧绑定
 *   ≥ 8 s          → 清除所有绑定并重启（按住满 8 s 立即执行）
 *   1–3 s          → 忽略（防误触）
 * 可选外接键（默认 -1 未接）：ESTOP_GPIO 按下即急停；PAIR_GPIO 按下即开配对窗口。
 * 按键 GPIO 配错时最多是按键不起作用，HID 与 GATT 照常工作。
 */
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "driver/gpio.h"
#include "board.h"
#include "protocol.h"
#include "ble_hand.h"
#include "action.h"
#include "buttons.h"

static const char *TAG = "btn";

#define POLL_MS     10
#define DEBOUNCE_MS 30

typedef struct {
    int gpio;
    bool pressed;
    int stable_ms;
    int64_t down_at_us;
    bool long_fired;
} btn_t;

static btn_t s_boot = { .gpio = BOARD_BOOT_BTN_GPIO };
static btn_t s_estop = { .gpio = BOARD_ESTOP_BTN_GPIO };
static btn_t s_pair = { .gpio = BOARD_PAIR_BTN_GPIO };

static bool cfg_input(int gpio)
{
    if (gpio < 0 || !GPIO_IS_VALID_GPIO(gpio)) return false;
    gpio_config_t c = {
        .pin_bit_mask = 1ULL << gpio,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
    };
    return gpio_config(&c) == ESP_OK;
}

/* 返回：+1 刚按下，-1 刚松开，0 无变化 */
static int poll(btn_t *b)
{
    if (b->gpio < 0) return 0;
    bool raw = gpio_get_level(b->gpio) == 0;
    if (raw == b->pressed) { b->stable_ms = 0; return 0; }
    b->stable_ms += POLL_MS;
    if (b->stable_ms < DEBOUNCE_MS) return 0;
    b->stable_ms = 0;
    b->pressed = raw;
    if (raw) { b->down_at_us = esp_timer_get_time(); b->long_fired = false; return 1; }
    return -1;
}

static void estop(int gpio, uint16_t ms)
{
    ESP_LOGW(TAG, "急停 (GPIO%d)", gpio);
    action_cancel();
    ble_hand_notify_button(BTN_EV_ESTOP, (uint8_t)gpio, ms);
}

static void pair(int gpio, uint16_t ms)
{
    ble_hand_open_pairing_window();
    ble_hand_notify_button(BTN_EV_PAIR_WINDOW, (uint8_t)gpio, ms);
}

static void btn_task(void *arg)
{
    for (;;) {
        int e = poll(&s_boot);
        if (s_boot.pressed && !s_boot.long_fired &&
            esp_timer_get_time() - s_boot.down_at_us >= 8000000LL) {
            s_boot.long_fired = true;
            ble_hand_notify_button(BTN_EV_RESET_BONDS, (uint8_t)s_boot.gpio, 8000);
            ble_hand_clear_bonds_and_restart();
        }
        if (e == -1 && !s_boot.long_fired) {
            int64_t ms = (esp_timer_get_time() - s_boot.down_at_us) / 1000;
            if (ms < 1000) estop(s_boot.gpio, (uint16_t)ms);
            else if (ms >= 3000) pair(s_boot.gpio, (uint16_t)ms);
        }
        if (poll(&s_estop) == 1) estop(s_estop.gpio, 0);
        if (poll(&s_pair) == 1) pair(s_pair.gpio, 0);
        vTaskDelay(pdMS_TO_TICKS(POLL_MS));
    }
}

void buttons_init(void)
{
    bool any = false;
    if (cfg_input(s_boot.gpio)) any = true; else s_boot.gpio = -1;
    if (cfg_input(s_estop.gpio)) any = true; else s_estop.gpio = -1;
    if (cfg_input(s_pair.gpio)) any = true; else s_pair.gpio = -1;
    if (!any) {
        ESP_LOGW(TAG, "没有可用按键，HID/GATT 照常工作");
        return;
    }
    xTaskCreate(btn_task, "btn", 3072, NULL, 4, NULL);
    ESP_LOGI(TAG, "按键 BOOT=%d ESTOP=%d PAIR=%d", s_boot.gpio, s_estop.gpio, s_pair.gpio);
}

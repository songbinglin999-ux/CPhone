/*
 * status_led.c —— 状态灯。默认驱动 DevKit 板载 WS2812（GPIO48，v1.1 板为 GPIO38）。
 *   未就绪 = 红色慢闪(1 Hz)   就绪 = 绿色常亮   通话中 = 蓝色常亮
 *   等待确认 = 黄色快闪(4 Hz) 故障 = 红色快闪(4 Hz)   配对窗口 = 紫色闪烁（叠加）
 * 普通单色 LED：常亮/慢闪/快闪 表达同样的五种状态。GPIO 配错或初始化失败只打日志，不影响 HID。
 */
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "driver/rmt_tx.h"
#include "sdkconfig.h"
#include "board.h"
#include "protocol.h"
#include "status_led.h"

static const char *TAG = "led";
static volatile uint8_t s_state = ST_NOT_READY;
static volatile bool s_pairing;

#if CONFIG_HAND_LED_WS2812
static rmt_channel_handle_t s_chan;
static rmt_encoder_handle_t s_enc;

static void ws2812_put(uint8_t r, uint8_t g, uint8_t b)
{
    if (!s_chan) return;
    uint8_t grb[3] = { g, r, b };
    rmt_transmit_config_t cfg = { .loop_count = 0 };
    if (rmt_transmit(s_chan, s_enc, grb, sizeof(grb), &cfg) == ESP_OK) {
        rmt_tx_wait_all_done(s_chan, 50);
    }
}

static bool hw_init(void)
{
    if (BOARD_LED_GPIO < 0) return false;
    rmt_tx_channel_config_t cc = {
        .gpio_num = BOARD_LED_GPIO,
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = 10 * 1000 * 1000, /* 0.1 us */
        .mem_block_symbols = 64,
        .trans_queue_depth = 2,
    };
    if (rmt_new_tx_channel(&cc, &s_chan) != ESP_OK) { s_chan = NULL; return false; }
    rmt_bytes_encoder_config_t ec = {
        .bit0 = { .level0 = 1, .duration0 = 3, .level1 = 0, .duration1 = 9 },
        .bit1 = { .level0 = 1, .duration0 = 9, .level1 = 0, .duration1 = 3 },
        .flags.msb_first = 1,
    };
    if (rmt_new_bytes_encoder(&ec, &s_enc) != ESP_OK) return false;
    return rmt_enable(s_chan) == ESP_OK;
}

static void show(bool on, uint8_t r, uint8_t g, uint8_t b)
{
    const uint8_t k = BOARD_LED_BRIGHTNESS;
    if (on) ws2812_put(r * k / 255, g * k / 255, b * k / 255);
    else ws2812_put(0, 0, 0);
}
#elif CONFIG_HAND_LED_PLAIN
static bool hw_init(void)
{
    if (BOARD_LED_GPIO < 0) return false;
    gpio_config_t c = { .pin_bit_mask = 1ULL << BOARD_LED_GPIO, .mode = GPIO_MODE_OUTPUT };
    return gpio_config(&c) == ESP_OK;
}
static void show(bool on, uint8_t r, uint8_t g, uint8_t b)
{
    (void)r; (void)g; (void)b;
    gpio_set_level(BOARD_LED_GPIO, on ? 1 : 0);
}
#else
static bool hw_init(void) { return false; }
static void show(bool on, uint8_t r, uint8_t g, uint8_t b) { (void)on; (void)r; (void)g; (void)b; }
#endif

void status_led_set(uint8_t state, bool pairing)
{
    s_state = state;
    s_pairing = pairing;
}

static void led_task(void *arg)
{
    uint32_t t = 0;  /* 50 ms 为一格 */
    for (;;) {
        bool slow = (t % 20) < 10;   /* 1 Hz */
        bool fast = (t % 5) < 2;     /* 4 Hz */
        if (s_pairing) {
            show((t % 10) < 5, 160, 0, 255);
        } else {
            switch (s_state) {
            case ST_READY:           show(true, 0, 255, 0); break;
            case ST_IN_CALL:         show(true, 0, 0, 255); break;
            case ST_WAITING_CONFIRM: show(fast, 255, 180, 0); break;
            case ST_FAULT:           show(fast, 255, 0, 0); break;
            default:                 show(slow, 255, 0, 0); break;
            }
        }
        t++;
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

void status_led_init(void)
{
    if (!hw_init()) {
        ESP_LOGW(TAG, "状态灯未启用（GPIO=%d），不影响 HID/GATT", BOARD_LED_GPIO);
        return;
    }
    xTaskCreate(led_task, "led", 3072, NULL, 2, NULL);
    ESP_LOGI(TAG, "状态灯 GPIO%d", BOARD_LED_GPIO);
}

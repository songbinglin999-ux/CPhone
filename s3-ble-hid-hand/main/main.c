/*
 * S3 Phone Hand —— ESP32-S3-DEV-KIT-N16R8-U 上的 BLE HID 键盘 + 鼠标「手」固件（第一期）。
 * 上电即广播，等待已绑定的手机连接；不依赖电脑或 USB（USB 仅用于烧录和看日志）。
 */
#include "esp_log.h"
#include "nvs_flash.h"
#include "board.h"
#include "protocol.h"
#include "ble_hand.h"
#include "action.h"
#include "status_led.h"
#include "buttons.h"
#include "wifi_hand.h"

static const char *TAG = "main";

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_LOGI(TAG, "S3 Phone Hand fw %s on %s", HAND_FW_VERSION, BOARD_NAME);
    status_led_init();   /* 可选外设，失败不影响后续 */
    action_init();
    ble_hand_init();
    buttons_init();      /* 可选外设，失败不影响后续 */
    wifi_hand_init();    /* 局域网 TCP 透传；连不上 Wi-Fi 不影响 BLE */
    wifi_hand_console_init();
}

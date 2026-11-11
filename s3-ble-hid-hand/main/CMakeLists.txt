idf_component_register(
    SRCS "main.c" "ble_hand.c" "hid_engine.c" "action.c" "status_led.c" "buttons.c" "wifi_hand.c" "console_cmd.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash esp_timer driver esp_driver_rmt esp_driver_gpio esp_driver_uart
             esp_wifi esp_netif esp_event lwip
)

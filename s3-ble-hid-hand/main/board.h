/*
 * board.h —— 板级引脚，全部集中在这里。
 *
 * 目标板：ESP32-S3-DEV-KIT-N16R8-U（标准 DevKit，不是定制探头板；没有音频编解码器/喇叭/麦克风）。
 *   - RGB 状态灯：WS2812，DevKitC-1 v1.0 及多数 N16R8 兼容板在 GPIO48；DevKitC-1 v1.1 在 GPIO38。
 *     TODO(用户)：对照自己板子丝印/原理图，在 menuconfig → S3 Phone Hand 里改 HAND_LED_GPIO。
 *   - BOOT 键：GPIO0（低电平有效，同时是启动下载模式的 strapping 脚，上电时不要按住）。
 *   - RESET/EN 键：直接复位芯片，固件无法读取，不用于任何功能。
 *   - GPIO35/36/37 被 Octal PSRAM 占用，不要配置为按键/灯。
 *
 * 引脚配错不会影响 HID 与 GATT：按键和灯都是可选外设，初始化失败只打日志。
 */
#pragma once
#include "sdkconfig.h"

#define BOARD_NAME              "ESP32-S3-DEV-KIT-N16R8-U"

#define BOARD_LED_GPIO          CONFIG_HAND_LED_GPIO          /* TODO: 48 或 38，以板子为准 */
#define BOARD_BOOT_BTN_GPIO     CONFIG_HAND_BOOT_BUTTON_GPIO  /* 板载 BOOT = GPIO0 */
#define BOARD_ESTOP_BTN_GPIO    CONFIG_HAND_ESTOP_BUTTON_GPIO /* TODO: 外接急停键，默认无 */
#define BOARD_PAIR_BTN_GPIO     CONFIG_HAND_PAIR_BUTTON_GPIO  /* TODO: 外接配对键，默认无 */

/* WS2812 亮度（0-255），板载灯很亮，取低值 */
#define BOARD_LED_BRIGHTNESS    24

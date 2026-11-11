#pragma once
#include <stdint.h>
#include <stdbool.h>
void status_led_init(void);
/* state = hand_state；pairing=true 时叠加紫色闪烁表示配对窗口打开 */
void status_led_set(uint8_t state, bool pairing);

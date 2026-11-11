#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "protocol.h"

void     ble_hand_init(void);
uint16_t ble_hand_conn_handle(void);
bool     ble_hand_is_encrypted(void);
bool     ble_hand_is_subscribed(uint16_t val_handle);
bool     ble_hand_hid_ready(void);           /* 已加密且系统已订阅键盘或鼠标报告 */
void     ble_hand_report_result(uint8_t seq, uint8_t opcode, uint8_t result);
void     ble_hand_notify_button(uint8_t event, uint8_t gpio, uint16_t press_ms);
void     ble_hand_open_pairing_window(void);
void     ble_hand_clear_bonds_and_restart(void);
void     ble_hand_get_status(hand_status_t *st); /* 当前 12 字节状态（与 GATT Status 相同） */

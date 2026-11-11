#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

void    action_init(void);
/* 解析并提交一条动作；立即返回 RES_ACCEPTED / RES_OK(仅 CANCEL/SET_*) / 错误码 */
uint8_t action_submit(const uint8_t *data, size_t len);

/* 指令来源：GATT（手机 App）或 Wi-Fi TCP（局域网透传）。两者共享同一个“一次一条、不排队”的执行器 */
enum action_src { ACTION_SRC_GATT = 0, ACTION_SRC_TCP = 1 };
/* tag 原样传回 wifi_hand_report_final（TCP 用它区分是哪一次客户端连接） */
uint8_t action_submit_from(const uint8_t *data, size_t len, uint8_t src, uint32_t tag);
/* 急停：中止当前动作、松开所有键（任何上下文都可调用） */
void    action_cancel(void);
bool    action_busy(void);

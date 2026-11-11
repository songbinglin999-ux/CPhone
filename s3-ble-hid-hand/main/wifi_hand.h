#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/*
 * wifi_hand —— 局域网透传：Wi-Fi STA + TCP 服务器（0.0.0.0:3333）+ 串口配置命令。
 * Wi-Fi 账号密码只在运行时通过串口设置，存 NVS 命名空间 "hand_wifi"（键 ssid / pass），不写进源码。
 * Wi-Fi 连不上不影响 BLE；断线后每 WIFI_RETRY_MS 重试一次。
 */
#define WIFI_HAND_NVS_NS     "hand_wifi"
#define WIFI_HAND_TCP_PORT   3333
#define WIFI_HAND_HOSTNAME   "s3-phone-hand"
#define WIFI_HAND_RETRY_MS   20000

void wifi_hand_init(void);      /* 启动 Wi-Fi、TCP 服务器、mDNS；失败只打日志 */
void wifi_hand_console_init(void); /* UART0 行命令：wifi_set / wifi_status / wifi_clear / help */

/* 设置并保存凭据，然后（重新）连接。pass 可为空串（开放网络）。返回 ESP_OK 或错误码 */
int  wifi_hand_set_credentials(const char *ssid, const char *pass);
int  wifi_hand_clear_credentials(void);
void wifi_hand_print_status(void);

/* action 任务在 TCP 来源的动作执行完后调用，把最终结果推给 TCP 客户端 */
void wifi_hand_report_final(uint8_t seq, uint8_t opcode, uint8_t result, uint32_t tag);

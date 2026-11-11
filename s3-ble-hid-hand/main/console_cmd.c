/*
 * console_cmd.c —— UART0（板上 CH343 USB-UART 口，115200 8N1）上的简单行命令，用来在不重新编译的情况下配置 Wi-Fi。
 *
 *   help
 *   wifi_set <ssid> <password>      参数可用 "双引号" 或 '单引号' 包住；双引号内 \" 和 \\ 转义
 *   wifi_set                        然后下一行输入 SSID、再下一行输入密码（整行原样，不做任何解析，适合含特殊字符的密码）
 *   wifi_set <ssid>                 然后下一行输入密码（整行原样）
 *   wifi_status                     SSID / 连接状态 / IP（永远不打印密码）
 *   wifi_clear                      删除保存的凭据并断开
 *
 * 固件不回显输入的字符（避免密码出现在串口记录里），每条命令执行后打印一行带密码遮罩的确认。
 */
#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_err.h"
#include "sdkconfig.h"
#include "wifi_hand.h"

static const char *TAG = "console";

#define CON_UART   CONFIG_ESP_CONSOLE_UART_NUM
#define CON_LINE_MAX   200

enum { ST_CMD, ST_WAIT_SSID, ST_WAIT_PASS };
static int  s_state = ST_CMD;
static char s_pending_ssid[33];

/* 把 line 就地拆成参数；支持引号与反斜杠转义。返回参数个数，-1 = 引号未闭合 */
static int tokenize(char *line, char **argv, int max)
{
    int argc = 0;
    char *r = line, *w = line;
    while (*r) {
        while (*r == ' ' || *r == '\t') r++;
        if (!*r) break;
        if (argc >= max) return max + 1;
        argv[argc++] = w;
        while (*r && *r != ' ' && *r != '\t') {
            if (*r == '"') {
                r++;
                while (*r && *r != '"') {
                    if (*r == '\\' && (r[1] == '"' || r[1] == '\\')) r++;
                    *w++ = *r++;
                }
                if (*r != '"') return -1;
                r++;
            } else if (*r == '\'') {
                r++;
                while (*r && *r != '\'') *w++ = *r++;
                if (*r != '\'') return -1;
                r++;
            } else if (*r == '\\' && r[1]) {
                r++;
                *w++ = *r++;
            } else {
                *w++ = *r++;
            }
        }
        if (*r) r++;
        *w++ = 0;
    }
    return argc;
}

static void do_set(const char *ssid, const char *pass)
{
    int e = wifi_hand_set_credentials(ssid, pass);
    if (e == ESP_OK) {
        printf("OK wifi_set ssid=\"%s\" password=******** saved to NVS, connecting...\n", ssid);
    } else if (e == ESP_ERR_INVALID_ARG) {
        printf("ERR wifi_set: SSID 长度须为 1..32\n");
    } else if (e == ESP_ERR_INVALID_SIZE) {
        printf("ERR wifi_set: 密码长度须为 0（开放网络）或 8..64\n");
    } else {
        printf("ERR wifi_set: %s\n", esp_err_to_name(e));
    }
}

static void print_help(void)
{
    printf("commands:\n"
           "  wifi_set <ssid> <password>   (用 \"..\" 包住含空格/特殊字符的参数)\n"
           "  wifi_set                     然后逐行输入 SSID、密码（整行原样）\n"
           "  wifi_status\n"
           "  wifi_clear\n"
           "  help\n");
}

static void handle_line(char *line)
{
    size_t n = strlen(line);
    if (s_state == ST_WAIT_SSID) {
        if (n == 0 || n > 32) { printf("ERR SSID 长度须为 1..32，已取消\n"); s_state = ST_CMD; return; }
        strlcpy(s_pending_ssid, line, sizeof(s_pending_ssid));
        printf("ssid=\"%s\"，请输入密码（整行，开放网络直接回车）:\n", s_pending_ssid);
        s_state = ST_WAIT_PASS;
        return;
    }
    if (s_state == ST_WAIT_PASS) {
        s_state = ST_CMD;
        do_set(s_pending_ssid, line);
        memset(s_pending_ssid, 0, sizeof(s_pending_ssid));
        return;
    }

    if (n == 0) return;
    char *argv[4];
    /* 先只看命令名，别把整行（可能含密码）打出来 */
    int argc = tokenize(line, argv, 3);
    if (argc < 0) { printf("ERR 引号未闭合\n"); return; }
    if (argc == 0) return;
    if (argc > 3) { printf("ERR 参数太多（SSID 或密码含空格请用引号）\n"); return; }

    if (!strcmp(argv[0], "wifi_set")) {
        if (argc == 3) {
            do_set(argv[1], argv[2]);
        } else if (argc == 2) {
            if (strlen(argv[1]) == 0 || strlen(argv[1]) > 32) { printf("ERR SSID 长度须为 1..32\n"); return; }
            strlcpy(s_pending_ssid, argv[1], sizeof(s_pending_ssid));
            printf("ssid=\"%s\"，请输入密码（整行，开放网络直接回车）:\n", s_pending_ssid);
            s_state = ST_WAIT_PASS;
        } else {
            printf("请输入 SSID（整行）:\n");
            s_state = ST_WAIT_SSID;
        }
    } else if (!strcmp(argv[0], "wifi_status")) {
        wifi_hand_print_status();
    } else if (!strcmp(argv[0], "wifi_clear")) {
        int e = wifi_hand_clear_credentials();
        printf(e == ESP_OK ? "OK wifi_clear\n" : "ERR wifi_clear: %s\n", esp_err_to_name(e));
    } else if (!strcmp(argv[0], "help") || !strcmp(argv[0], "?")) {
        print_help();
    } else {
        printf("ERR 未知命令 \"%s\"，输入 help\n", argv[0]);
    }
}

static void console_task(void *arg)
{
    (void)arg;
    static char line[CON_LINE_MAX + 1];
    size_t n = 0;
    bool overflow = false;
    uint8_t c, prev = 0;
    for (;;) {
        if (uart_read_bytes(CON_UART, &c, 1, portMAX_DELAY) != 1) continue;
        if (c == '\n' && prev == '\r') { prev = c; continue; }   /* \r\n 只算一次 */
        prev = c;
        if (c == '\r' || c == '\n') {
            if (overflow) {
                printf("ERR 行太长（>%d），已丢弃\n", CON_LINE_MAX);
            } else if (n > 0 || s_state != ST_CMD) {   /* 等待密码时空行 = 开放网络 */
                line[n] = 0;
                handle_line(line);
            }
            memset(line, 0, sizeof(line));
            n = 0;
            overflow = false;
            continue;
        }
        if (c == 0x08 || c == 0x7f) { if (n) n--; continue; }
        if (c < 0x20 && c != '\t') continue;
        if (n < CON_LINE_MAX) line[n++] = (char)c;
        else overflow = true;
    }
}

void wifi_hand_console_init(void)
{
    esp_err_t e = uart_driver_install(CON_UART, 512, 0, 0, NULL, 0);
    if (e != ESP_OK) { ESP_LOGE(TAG, "uart_driver_install: %s", esp_err_to_name(e)); return; }
    xTaskCreate(console_task, "console", 4096, NULL, 3, NULL);
    ESP_LOGI(TAG, "串口命令已就绪（UART%d）：help / wifi_set / wifi_status / wifi_clear", CON_UART);
}

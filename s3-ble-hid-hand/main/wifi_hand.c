/*
 * wifi_hand.c —— Wi-Fi STA + TCP 动作透传服务器。
 *
 * TCP 帧格式（全部小端）：
 *   请求：[u16 len][len 字节]，len 字节就是和 GATT Action 特征完全相同的动作包 [u8 opcode][u8 seq][payload...]。
 *   回复：[u16 len][u8 seq][u8 result][u8 opcode][u8 phase][附加数据...]
 *         phase 0 = 提交结果（立刻返回：ACCEPTED=8 / OK / 各种错误码）
 *         phase 1 = 执行完的最终结果（仅当 phase 0 返回 ACCEPTED 时才会有）
 *   仅 TCP 支持的查询：opcode 0x40 (OP_TCP_STATUS)，回复 phase 0、result OK，附加 12 字节 hand_status_t。
 *
 * 规则与 GATT 一样：一次一条、不排队（BUSY）；时长越界返回 BAD_PARAM，不夹紧。
 * GATT 与 TCP 共用同一个执行器，谁先提交谁执行。CANCEL(0x7F) 永远立即生效。
 * 同一时间只服务一个 TCP 客户端；客户端在动作执行中断开 → 自动 CANCEL（防止无人看管）。
 *
 * 注意：TCP 通道没有加密和认证，只适合在自己家的局域网里调试 / 控制。
 */
#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_err.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "esp_timer.h"
#include "nvs.h"
#include "lwip/sockets.h"
#include "lwip/netdb.h"
#include "mdns.h"

#include "protocol.h"
#include "action.h"
#include "ble_hand.h"
#include "wifi_hand.h"

static const char *TAG = "wifi";

#define TCP_MAX_FRAME 256

static esp_netif_t *s_netif;
static bool s_wifi_ok;               /* 驱动初始化成功 */
static SemaphoreHandle_t s_cfg_mtx;
static char s_ssid[33];
static bool s_have_creds;
static volatile bool s_connected;
static volatile bool s_got_ip;
static esp_ip4_addr_t s_ip;
static volatile uint8_t s_last_reason;
static volatile uint32_t s_attempts;
static esp_timer_handle_t s_retry_timer;

/* TCP 客户端 */
static SemaphoreHandle_t s_tx_mtx;   /* 保护 s_client / s_client_gen / 发送顺序 */
static int s_client = -1;
static uint32_t s_client_gen;
static int s_tcp_inflight;           /* 已 ACCEPTED、尚未出最终结果的 TCP 动作数 */

/* ===================== NVS ===================== */

static esp_err_t nvs_load(char *ssid, size_t ssid_sz, char *pass, size_t pass_sz)
{
    nvs_handle_t h;
    esp_err_t e = nvs_open(WIFI_HAND_NVS_NS, NVS_READONLY, &h);
    if (e != ESP_OK) return e;
    size_t n = ssid_sz;
    e = nvs_get_str(h, "ssid", ssid, &n);
    if (e == ESP_OK) {
        n = pass_sz;
        e = nvs_get_str(h, "pass", pass, &n);
    }
    nvs_close(h);
    return e;
}

static esp_err_t nvs_save(const char *ssid, const char *pass)
{
    nvs_handle_t h;
    esp_err_t e = nvs_open(WIFI_HAND_NVS_NS, NVS_READWRITE, &h);
    if (e != ESP_OK) return e;
    e = nvs_set_str(h, "ssid", ssid);
    if (e == ESP_OK) e = nvs_set_str(h, "pass", pass);
    if (e == ESP_OK) e = nvs_commit(h);
    nvs_close(h);
    return e;
}

/* ===================== STA ===================== */

static void apply_and_connect(const char *ssid, const char *pass)
{
    wifi_config_t wc = { 0 };
    strlcpy((char *)wc.sta.ssid, ssid, sizeof(wc.sta.ssid));
    /* 64 字符的十六进制 PSK 不带结尾 0，用 memcpy */
    size_t pl = strlen(pass);
    memcpy(wc.sta.password, pass, pl > sizeof(wc.sta.password) ? sizeof(wc.sta.password) : pl);
    wc.sta.threshold.authmode = pl ? WIFI_AUTH_WPA_PSK : WIFI_AUTH_OPEN;
    wc.sta.pmf_cfg.capable = true;
    wc.sta.pmf_cfg.required = false;
    wc.sta.sae_pwe_h2e = WPA3_SAE_PWE_BOTH;
    wc.sta.scan_method = WIFI_ALL_CHANNEL_SCAN;
    wc.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;

    esp_timer_stop(s_retry_timer);
    esp_wifi_disconnect();
    esp_err_t e = esp_wifi_set_config(WIFI_IF_STA, &wc);
    memset(&wc, 0, sizeof(wc));        /* 不在栈上留密码 */
    if (e != ESP_OK) { ESP_LOGE(TAG, "set_config: %s", esp_err_to_name(e)); return; }
    s_attempts++;
    ESP_LOGI(TAG, "connecting to \"%s\" ...", ssid);
    e = esp_wifi_connect();
    if (e != ESP_OK) {
        ESP_LOGW(TAG, "connect: %s，%d s 后重试", esp_err_to_name(e), WIFI_HAND_RETRY_MS / 1000);
        esp_timer_start_once(s_retry_timer, (uint64_t)WIFI_HAND_RETRY_MS * 1000);
    }
}

static void retry_cb(void *arg)
{
    (void)arg;
    if (!s_have_creds || s_connected) return;
    s_attempts++;
    ESP_LOGI(TAG, "重试连接 \"%s\"（第 %lu 次）", s_ssid, (unsigned long)s_attempts);
    if (esp_wifi_connect() != ESP_OK) {
        esp_timer_start_once(s_retry_timer, (uint64_t)WIFI_HAND_RETRY_MS * 1000);
    }
}

static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_CONNECTED) {
        s_connected = true;
        esp_timer_stop(s_retry_timer);
        ESP_LOGI(TAG, "已连上 AP，等待 DHCP");
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *d = data;
        bool was = s_got_ip;
        s_connected = false;
        s_got_ip = false;
        s_last_reason = d->reason;
        if (was) ESP_LOGW(TAG, "Wi-Fi 断开 reason=%u", d->reason);
        else ESP_LOGW(TAG, "连接失败 reason=%u", d->reason);
        if (s_have_creds) {
            esp_timer_stop(s_retry_timer);
            esp_timer_start_once(s_retry_timer, (uint64_t)WIFI_HAND_RETRY_MS * 1000);
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *e = data;
        s_ip = e->ip_info.ip;
        s_got_ip = true;
        ESP_LOGI(TAG, "GOT IP " IPSTR "  →  TCP %d 端口已就绪（mDNS: %s.local）",
                 IP2STR(&s_ip), WIFI_HAND_TCP_PORT, WIFI_HAND_HOSTNAME);
    } else if (base == IP_EVENT && id == IP_EVENT_STA_LOST_IP) {
        s_got_ip = false;
        ESP_LOGW(TAG, "IP 丢失");
    }
}

int wifi_hand_set_credentials(const char *ssid, const char *pass)
{
    size_t sl = strlen(ssid), pl = strlen(pass);
    if (sl == 0 || sl > 32) return ESP_ERR_INVALID_ARG;
    if (!(pl == 0 || (pl >= 8 && pl <= 64))) return ESP_ERR_INVALID_SIZE;
    if (!s_wifi_ok) return ESP_ERR_INVALID_STATE;
    esp_err_t e = nvs_save(ssid, pass);
    if (e != ESP_OK) return e;
    xSemaphoreTake(s_cfg_mtx, portMAX_DELAY);
    strlcpy(s_ssid, ssid, sizeof(s_ssid));
    s_have_creds = true;
    s_attempts = 0;
    apply_and_connect(ssid, pass);
    xSemaphoreGive(s_cfg_mtx);
    return ESP_OK;
}

int wifi_hand_clear_credentials(void)
{
    nvs_handle_t h;
    esp_err_t e = nvs_open(WIFI_HAND_NVS_NS, NVS_READWRITE, &h);
    if (e == ESP_OK) {
        nvs_erase_all(h);
        e = nvs_commit(h);
        nvs_close(h);
    }
    xSemaphoreTake(s_cfg_mtx, portMAX_DELAY);
    s_have_creds = false;
    s_ssid[0] = 0;
    if (s_wifi_ok) {
        esp_timer_stop(s_retry_timer);
        esp_wifi_disconnect();
        wifi_config_t wc = { 0 };
        esp_wifi_set_config(WIFI_IF_STA, &wc);
    }
    xSemaphoreGive(s_cfg_mtx);
    return e;
}

void wifi_hand_print_status(void)
{
    printf("wifi_status: fw=%s\n", HAND_FW_VERSION);
    if (!s_wifi_ok) { printf("  wifi: 驱动未初始化（见启动日志）\n"); return; }
    printf("  ssid: %s\n", s_have_creds ? s_ssid : "(未设置，用 wifi_set 设置)");
    printf("  password: %s\n", s_have_creds ? "(已保存，不显示)" : "-");
    const char *st = !s_have_creds ? "no_credentials" : s_got_ip ? "connected" : s_connected ? "associated_no_ip" : "disconnected_retrying";
    printf("  state: %s\n", st);
    if (s_got_ip) {
        printf("  ip: " IPSTR "\n", IP2STR(&s_ip));
        wifi_ap_record_t ap;
        if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) printf("  rssi: %d dBm  channel: %u\n", ap.rssi, ap.primary);
    } else {
        printf("  ip: -\n");
        if (s_last_reason) printf("  last_disconnect_reason: %u\n", s_last_reason);
    }
    printf("  attempts: %lu  retry_every: %d s\n", (unsigned long)s_attempts, WIFI_HAND_RETRY_MS / 1000);
    printf("  tcp: 0.0.0.0:%d  client: %s  mdns: %s.local\n", WIFI_HAND_TCP_PORT,
           s_client >= 0 ? "connected" : "none", WIFI_HAND_HOSTNAME);
    printf("  ble_hid_ready: %d  action_busy: %d\n", ble_hand_hid_ready(), action_busy());
}

/* ===================== TCP ===================== */

static int send_all(int fd, const uint8_t *b, size_t n)
{
    while (n > 0) {
        int r = send(fd, b, n, 0);
        if (r <= 0) return -1;
        b += r;
        n -= (size_t)r;
    }
    return 0;
}

static int recv_all(int fd, uint8_t *b, size_t n)
{
    while (n > 0) {
        int r = recv(fd, b, n, 0);
        if (r <= 0) return -1;
        b += r;
        n -= (size_t)r;
    }
    return 0;
}

/* 调用者持有 s_tx_mtx */
static void send_reply_locked(uint8_t seq, uint8_t result, uint8_t op, uint8_t phase, const void *extra, size_t extra_len)
{
    if (s_client < 0) return;
    uint8_t buf[2 + 4 + sizeof(hand_status_t)];
    size_t body = 4 + extra_len;
    if (body + 2 > sizeof(buf)) return;
    buf[0] = (uint8_t)(body & 0xff);
    buf[1] = (uint8_t)(body >> 8);
    buf[2] = seq; buf[3] = result; buf[4] = op; buf[5] = phase;
    if (extra_len) memcpy(buf + 6, extra, extra_len);
    if (send_all(s_client, buf, body + 2) != 0) ESP_LOGW(TAG, "TCP 发送失败");
}

void wifi_hand_report_final(uint8_t seq, uint8_t opcode, uint8_t result, uint32_t tag)
{
    if (!s_tx_mtx) return;
    xSemaphoreTake(s_tx_mtx, portMAX_DELAY);
    if (s_tcp_inflight > 0) s_tcp_inflight--;
    if (tag == s_client_gen) send_reply_locked(seq, result, opcode, 1, NULL, 0); /* 只发给提交它的那次连接 */
    xSemaphoreGive(s_tx_mtx);
}

static void handle_frame(const uint8_t *f, size_t n)
{
    uint8_t op = n >= 1 ? f[0] : 0;
    uint8_t seq = n >= 2 ? f[1] : 0;
    xSemaphoreTake(s_tx_mtx, portMAX_DELAY);
    if (n >= 2 && op == OP_TCP_STATUS) {
        hand_status_t st;
        ble_hand_get_status(&st);
        send_reply_locked(seq, n == 2 ? RES_OK : RES_BAD_PARAM, op, 0, n == 2 ? &st : NULL, n == 2 ? sizeof(st) : 0);
    } else {
        /* 持锁提交 + 回 phase 0，保证 phase 0 一定先于 phase 1 发出 */
        uint8_t r = action_submit_from(f, n, ACTION_SRC_TCP, s_client_gen);
        if (r == RES_ACCEPTED) s_tcp_inflight++;
        if (n >= 2) ble_hand_report_result(seq, op, r);
        send_reply_locked(seq, r, op, 0, NULL, 0);
        ESP_LOGI(TAG, "tcp op=0x%02x seq=%u submit=%u", op, seq, r);
    }
    xSemaphoreGive(s_tx_mtx);
}

static void serve_client(int fd)
{
    uint8_t buf[TCP_MAX_FRAME];
    for (;;) {
        uint8_t hdr[2];
        if (recv_all(fd, hdr, 2) != 0) break;
        size_t len = hdr[0] | (hdr[1] << 8);
        if (len == 0) continue;              /* 空帧当作保活，忽略 */
        if (len > TCP_MAX_FRAME) {
            /* 超长：读掉丢弃，回 BAD_PARAM，连接继续 */
            size_t left = len;
            uint8_t first2[2] = {0, 0};
            size_t got = 0;
            while (left) {
                size_t c = left > sizeof(buf) ? sizeof(buf) : left;
                if (recv_all(fd, buf, c) != 0) goto out;
                if (got < 2) { memcpy(first2, buf, 2); got = 2; }
                left -= c;
            }
            xSemaphoreTake(s_tx_mtx, portMAX_DELAY);
            send_reply_locked(first2[1], RES_BAD_PARAM, first2[0], 0, NULL, 0);
            xSemaphoreGive(s_tx_mtx);
            continue;
        }
        if (recv_all(fd, buf, len) != 0) break;
        handle_frame(buf, len);
    }
out:
    return;
}

static void tcp_task(void *arg)
{
    (void)arg;
    for (;;) {
        int ls = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
        if (ls < 0) { vTaskDelay(pdMS_TO_TICKS(1000)); continue; }
        int one = 1;
        setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(WIFI_HAND_TCP_PORT),
                                 .sin_addr.s_addr = htonl(INADDR_ANY) };
        if (bind(ls, (struct sockaddr *)&a, sizeof(a)) != 0 || listen(ls, 1) != 0) {
            ESP_LOGE(TAG, "TCP bind/listen 失败 errno=%d", errno);
            close(ls);
            vTaskDelay(pdMS_TO_TICKS(2000));
            continue;
        }
        ESP_LOGI(TAG, "TCP 服务器监听 0.0.0.0:%d", WIFI_HAND_TCP_PORT);
        for (;;) {
            struct sockaddr_in peer;
            socklen_t pl = sizeof(peer);
            int fd = accept(ls, (struct sockaddr *)&peer, &pl);
            if (fd < 0) { vTaskDelay(pdMS_TO_TICKS(100)); continue; }
            int ka = 1, idle = 10, intvl = 5, cnt = 3, nd = 1;
            setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &ka, sizeof(ka));
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sizeof(cnt));
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &nd, sizeof(nd));
            struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
            char ip[16];
            inet_ntoa_r(peer.sin_addr, ip, sizeof(ip));
            ESP_LOGI(TAG, "TCP 客户端 %s 已连接", ip);

            xSemaphoreTake(s_tx_mtx, portMAX_DELAY);
            s_client = fd;
            s_client_gen++;
            xSemaphoreGive(s_tx_mtx);

            serve_client(fd);

            xSemaphoreTake(s_tx_mtx, portMAX_DELAY);
            bool pending = s_tcp_inflight > 0;
            s_client = -1;
            s_client_gen++;
            xSemaphoreGive(s_tx_mtx);
            if (pending) {
                ESP_LOGW(TAG, "TCP 客户端在动作执行中断开 → CANCEL");
                action_cancel();
            }
            shutdown(fd, SHUT_RDWR);
            close(fd);
            ESP_LOGI(TAG, "TCP 客户端 %s 已断开", ip);
        }
    }
}

/* ===================== 初始化 ===================== */

static void start_mdns(void)
{
    esp_err_t e = mdns_init();
    if (e != ESP_OK) { ESP_LOGW(TAG, "mDNS 初始化失败: %s", esp_err_to_name(e)); return; }
    mdns_hostname_set(WIFI_HAND_HOSTNAME);
    mdns_instance_name_set("S3 Phone Hand");
    mdns_txt_item_t txt[] = { { "fw", HAND_FW_VERSION }, { "proto", "1" } };
    mdns_service_add("S3 Phone Hand", "_s3hand", "_tcp", WIFI_HAND_TCP_PORT, txt, 2);
}

void wifi_hand_init(void)
{
    s_cfg_mtx = xSemaphoreCreateMutex();
    s_tx_mtx = xSemaphoreCreateMutex();
    const esp_timer_create_args_t rt = { .callback = retry_cb, .name = "wifi_retry" };
    if (esp_timer_create(&rt, &s_retry_timer) != ESP_OK) { ESP_LOGE(TAG, "timer 创建失败"); return; }

    esp_err_t e = esp_netif_init();
    if (e == ESP_OK) e = esp_event_loop_create_default();
    if (e == ESP_ERR_INVALID_STATE) e = ESP_OK;  /* 事件循环已存在 */
    if (e != ESP_OK) { ESP_LOGE(TAG, "netif/event 初始化失败: %s", esp_err_to_name(e)); return; }
    s_netif = esp_netif_create_default_wifi_sta();
    if (!s_netif) { ESP_LOGE(TAG, "创建 STA netif 失败"); return; }
    esp_netif_set_hostname(s_netif, WIFI_HAND_HOSTNAME);

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    e = esp_wifi_init(&cfg);
    if (e != ESP_OK) { ESP_LOGE(TAG, "esp_wifi_init: %s（BLE 不受影响）", esp_err_to_name(e)); return; }
    esp_wifi_set_storage(WIFI_STORAGE_RAM);      /* 凭据只放我们自己的 hand_wifi 命名空间 */
    esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_wifi_event, NULL, NULL);
    esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_wifi_event, NULL, NULL);
    esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_LOST_IP, on_wifi_event, NULL, NULL);
    esp_wifi_set_mode(WIFI_MODE_STA);
    e = esp_wifi_start();
    if (e != ESP_OK) { ESP_LOGE(TAG, "esp_wifi_start: %s（BLE 不受影响）", esp_err_to_name(e)); return; }
    /* 和 BLE 共存必须保持 modem sleep（默认 MIN_MODEM），不要改成 WIFI_PS_NONE */
    esp_wifi_set_ps(WIFI_PS_MIN_MODEM);
    s_wifi_ok = true;

    start_mdns();
    xTaskCreatePinnedToCore(tcp_task, "tcp_hand", 4096, NULL, 4, NULL, 0);

    char ssid[33] = { 0 }, pass[65] = { 0 };
    if (nvs_load(ssid, sizeof(ssid), pass, sizeof(pass)) == ESP_OK && ssid[0]) {
        xSemaphoreTake(s_cfg_mtx, portMAX_DELAY);
        strlcpy(s_ssid, ssid, sizeof(s_ssid));
        s_have_creds = true;
        apply_and_connect(ssid, pass);
        xSemaphoreGive(s_cfg_mtx);
    } else {
        ESP_LOGW(TAG, "NVS 里没有 Wi-Fi 凭据：串口输入  wifi_set \"SSID\" \"密码\"  设置（BLE 照常工作）");
    }
    memset(pass, 0, sizeof(pass));
}

# S3 Phone Hand —— ESP32-S3 蓝牙键鼠「手」固件（v1.1.0：BLE HID + Wi-Fi TCP 局域网透传）

> **v1.1.0 新增**：ESP32 连家里 Wi-Fi，电脑在局域网里用 TCP（端口 3333）直接发动作包，ESP32 照样用 BLE HID 在手机上执行。
> BLE 部分（HID 键鼠、自定义 GATT、绑定）与 1.0.0 完全一样，已配对的手机**不用重新配对**。详见第 11 节。
> v1.1.0 只在电脑上编译通过，**尚未烧录、尚未真机验证 Wi-Fi 与 BLE 共存**。

家里两件套中的 ESP32 这一半：**备用机荣耀 Play4T Pro（AQM-AL10，Android 10 / HarmonyOS 2.2）+ ESP32-S3**。
运行时 ESP32 自己供电，只通过 BLE 和手机配合，手机 USB-C 一直插着充电器。ESP32 以 **BLE HID 键盘 + 鼠标（HID over GATT）**
的身份把点击、滑动、打字注入手机；手机上的前台服务以后通过 **自定义 GATT 服务**给它发动作指令。

> ⚠️ 这份固件**只在电脑上编译通过，尚未烧录、尚未在 AQM-AL10 上验证**。HID 键鼠在这台手机上能不能用、
> 鼠标是不是按 1:1 移动、配成系统键鼠之后 App 还能不能连自定义服务，都要真机验证（见第 9 节）。

## 1. 目标硬件

| 项目 | 值 |
|---|---|
| 板子 | **ESP32-S3-DEV-KIT-N16R8-U**（标准 DevKit，不是定制探头 PCB；**没有**音频编解码器、喇叭、麦克风） |
| 模组 | ESP32-S3-WROOM-1**U**-N16R8：16 MB Quad Flash + 8 MB Octal PSRAM |
| 天线 | “U” 表示**外接天线（IPEX/U.FL）**：运行前务必接上天线，否则 BLE 距离会非常短 |
| USB 口 | 两个 Type-C：**UART 口（WCH CH343，1a86:55d3）**用于烧录和看日志（推荐）；**原生 USB 口（Espressif 303a:xxxx）**也可烧录 |

“S3 Phone Audio Probe”只是这块板子之前那份 USB 小工具固件的名字。本固件**不含**任何音频 / UAC / 编解码器代码，
广播名也换成了 **`S3 Phone Hand`**，便于和旧固件区分。

## 2. 引脚（全部在 `main/board.h` + `menuconfig → S3 Phone Hand`）

| 功能 | 默认 GPIO | 说明 |
|---|---|---|
| 状态灯（WS2812 RGB） | **GPIO48** | DevKitC-1 v1.0 和多数 N16R8 兼容板是 48；**DevKitC-1 v1.1 是 GPIO38**。请对照你板子丝印/原理图修改 |
| 多功能按键 | **GPIO0（BOOT 键）** | 低电平有效；上电时不要按住（会进下载模式） |
| 额外急停键 | 未接（-1） | 可选，外接到 GND 的按键 |
| 额外配对键 | 未接（-1） | 可选 |
| RESET/EN 键 | — | 直接复位芯片，固件读不到 |
| 禁用 | GPIO35/36/37 | 被 Octal PSRAM 占用 |

**注意：** 灯和按键的默认值只是“标准 DevKit 的常见接法”，在依赖它们之前请先核对你的板子。
**引脚配错不会影响 HID 和 GATT**——灯和按键都是可选外设，初始化失败只打日志。

### 状态灯

| 状态 | 颜色 |
|---|---|
| 0 未就绪（未连接 / 未加密 / 系统尚未订阅 HID 报告） | 红色慢闪 1 Hz |
| 1 就绪 | 绿色常亮 |
| 2 通话中 | 蓝色常亮 |
| 3 等待确认 | 黄色快闪 4 Hz |
| 4 故障 | 红色快闪 4 Hz |
| 配对窗口打开（叠加） | 紫色闪烁 |

手机通过 LED 特征设置 1–4；只要 BLE/HID 链路不完整，固件一律显示「未就绪」。

### BOOT 键（按松开时的按住时长判定）

| 按住 | 动作 |
|---|---|
| < 1 s | **急停**：立即中止当前动作、松开所有键，并通知手机 `BTN_EV_ESTOP` |
| 1–3 s | 忽略（防误触） |
| 3–8 s | **打开配对窗口**（默认 60 s，紫灯闪），允许新手机配对并替换旧绑定 |
| ≥ 8 s | **清除绑定并重启**（按满 8 s 立即执行） |

人在外面摸不到按键；车上取消仍靠电话里说「取消」，由手机发 `CANCEL`。

## 3. 编译

需要 **ESP-IDF v5.3.x**（在 v5.3.2 上编译通过，0 警告；5.1+ 预计也可以）。

```bash
. ~/esp/esp-idf/export.sh
cd s3-ble-hid-hand
idf.py set-target esp32s3      # 第一次
idf.py build
```

`sdkconfig.defaults` 已设好：16 MB Flash（QIO 80 MHz）、8 MB Octal PSRAM、NimBLE 仅外设、单连接、单绑定、绑定存 NVS、
FreeRTOS 1 kHz tick、日志走 UART0（CH343 口）。改引脚：`idf.py menuconfig` → **S3 Phone Hand**。

可选：`CONFIG_HAND_ENABLE_DIGITIZER=y` 额外提供单点触摸屏报告（见第 6 节），也已编译通过。

## 4. 以后由你自己烧录（本次没有烧录）

1. 用 Type-C 线接板子的 **UART 口（CH343）**。macOS 上一般是 `/dev/cu.wchusbserial*` 或 `/dev/cu.usbmodem*`。
2. ```bash
   idf.py -p /dev/cu.wchusbserialXXXX flash monitor
   ```
   如果自动进不了下载模式：按住 BOOT，点一下 RESET，再松开 BOOT。
3. **这会覆盖板子上原来的 “S3 Phone Audio Probe” 固件。** 想保留旧固件，先自己备份：
   `esptool.py -p PORT read_flash 0 0x1000000 probe_backup.bin`。
4. 不想装 ESP-IDF 的话，`prebuilt/` 里有默认配置（灯 GPIO48）的镜像和 `FLASH.md` 里的 esptool 命令，可校验 `SHA256SUMS`。
5. 烧完拔掉电脑，改用独立 5 V 供电（充电头/移动电源）。上电即广播，不依赖电脑和 USB。

## 5. 配对

1. 板子上电，首次没有绑定时自动允许第一台手机配对（红灯慢闪）。
2. 手机 设置 → 蓝牙 → 搜索 **`S3 Phone Hand`** → 配对（Just Works，无 PIN）。系统应把它识别成键盘/鼠标（输入设备）。
3. 配对成功后只接受这台手机；其它手机即使连上，加密后发现不是已绑定设备会被断开。
4. 换手机或手机侧删除了配对：长按 BOOT 3–8 s 打开 60 s 配对窗口后再配；或按住 ≥ 8 s 清除绑定。
5. 连接后 30 s 内未完成加密会被断开。所有 HID 和自定义特征都要求加密链路。

> Just Works 配对没有防中间人；所以配对窗口只在“无绑定”或你按键后才开放。没有做任何针对鸿蒙后台 BLE 限制的绕过。

## 6. GATT 结构

- **Device Information（0x180A）**：PnP ID（VID 0x303A Espressif / PID 0x4801）、厂商、型号 `ESP32-S3-DEV-KIT-N16R8-U`、固件版本
- **HID（0x1812）**：HID Information、Report Map、HID Control Point、Protocol Mode、以下报告
  - Report ID 1 键盘输入（8 字节）+ 键盘 LED 输出
  - Report ID 2 鼠标输入（7 字节：按钮、X int16、Y int16、滚轮 int8、横向滚动 int8），**相对坐标**
  - Report ID 3 Consumer Control（2 字节 usage；0x0223 AC Home、0x0224 AC Back）
  - Report ID 4 单点触摸屏（仅 `CONFIG_HAND_ENABLE_DIGITIZER`，绝对坐标 0..32767；**可选，非默认**）
- **自定义服务 `950f0001-28bb-4f78-8d4f-dae2ba3d0544`**（全部要求加密）

| 特征 | UUID | 属性 | 内容 |
|---|---|---|---|
| Action | `950f0002-28bb-4f78-8d4f-dae2ba3d0544` | Write / Write NR | 动作指令 |
| Status | `950f0003-28bb-4f78-8d4f-dae2ba3d0544` | Read / Notify | 12 字节状态，每 1 s 心跳 + 每条动作的结果 |
| LED | `950f0004-28bb-4f78-8d4f-dae2ba3d0544` | Read / Write | 1 字节状态 0–4 |
| Button | `950f0005-28bb-4f78-8d4f-dae2ba3d0544` | Read / Notify | 4 字节按键事件 |
| Info | `950f0006-28bb-4f78-8d4f-dae2ba3d0544` | Read | ASCII，如 `fw=1.1.0;proto=1;name=S3 Phone Hand;board=...;digitizer=0;wifi_tcp=1` |

广播包：Flags + Appearance 0x03C0（通用 HID）+ UUID16 0x1812 + 名称 `S3 Phone Hand`；扫描响应：自定义服务 UUID。

**BLE 上不收发 API Key、截图、聊天记录。** 唯一允许的文字是「要输入到当前焦点框」的那段内容（例如已经口头确认要发出的回复），作为 `TYPE_TEXT` 指令发送。

## 7. 二进制协议（版本 1，全部小端）

### Action：`[u8 opcode][u8 seq][payload]`

一次只执行一条，不排队。写入后固件立即通过 Status 回一次 `last_result`（`ACCEPTED`=8 或错误码），执行完再回一次最终结果。手机等到最终结果再发下一条。

| opcode | 名称 | payload | 说明 |
|---|---|---|---|
| 0x01 | CLICK_ABS | u16 x, u16 y, u16 hold_ms | 像素坐标；hold 0=90，允许 60–120 |
| 0x02 | LONGPRESS_ABS | u16 x, u16 y, u16 hold_ms | hold 0=750，允许 620–900 |
| 0x03 | SWIPE_ABS | u16 x1, y1, x2, y2, u16 dur_ms | dur 0=300，允许 200–450；直线 |
| 0x10 | MOVE_REL | i16 dx, i16 dy, u16 dur_ms | 只移动光标（鼠标计数）；dur 0=按限速自动，≤5000 |
| 0x11 | CLICK_HERE | u16 hold_ms | 当前光标处左键 |
| 0x12 | LONGPRESS_HERE | u16 hold_ms | |
| 0x13 | DRAG_REL | i16 dx, i16 dy, u16 dur_ms | 按住左键直线拖动，200–450 |
| 0x14 | WHEEL | i8 v, i8 h | 一份滚轮报告 |
| 0x20 | TYPE_TEXT | u16 gap_ms, bytes… | 逐键间隔 0=50，允许 30–80；只支持可打印 ASCII 与 `\n` `\t` `\b`（美式布局）；含其它字符**整条拒绝**（6） |
| 0x21 | KEY | u8 modifiers, u8 usage, u16 hold_ms | 单个组合键，如 Ctrl+V = `01 19`；hold 0=20，10–300 |
| 0x22 | CONSUMER | u16 usage | 0x0223 Home，0x0224 Back |
| 0x30 | SET_SCREEN | u16 w, u16 h, u16 counts_per_px_milli, u16 max_step | 绝对坐标前必须先设；换算系数 0=1000（1 计数/像素），max_step 0=4 |
| 0x31 | HOME_CURSOR | — | 把光标推到左上角 (0,0) |
| 0x7F | CANCEL | — | 急停：中止当前动作并松开所有键/按钮，永不 BUSY |

坐标必须 `x < w, y < h`，否则 `BAD_PARAM`。时长超出允许范围一律 `BAD_PARAM`（不悄悄夹紧）。

### Status（12 字节）

`u8 proto(=1) | u8 state | u8 flags | u8 last_seq | u8 last_opcode | u8 last_result | u16 mtu | u32 heartbeat`

- state：0 未就绪 / 1 就绪 / 2 通话中 / 3 等待确认 / 4 故障（链路不完整时强制 0）
- flags：bit0 已连接、bit1 已加密、bit2 已绑定、bit3 系统已订阅键盘、bit4 系统已订阅鼠标/触摸、bit5 忙、bit6 配对窗口、bit7 启用了 Digitizer
- result：0 OK、1 BUSY、2 BAD_PARAM、3 CANCELLED、4 NOT_READY、5 UNSUPPORTED、6 NON_ASCII、7 HID_ERROR、8 ACCEPTED
- heartbeat 每秒 +1。手机 3 s 收不到就应判「两件套不完整」。

### Button（4 字节）

`u8 event | u8 gpio | u16 press_ms`；event：1 急停、2 配对窗口已打开、3 清绑定并重启（通常来不及送达）。

### LED（1 字节）

写 0–4，其它值返回 “Value Not Allowed”。

`tools/hand_proto.py` 是打包/解包参考实现（不连设备）。

## 8. 时序规则（固件里写死，代码注释同样写明）

**允许——只为让系统认得出、输入法不丢字：**
- 普通点击按下保持 60–120 ms（默认 90 ms，固定值，不加随机）
- 长按 620–900 ms（默认 750 ms）。Android 长按阈值约 500 ms，给 BLE 调度抖动留足余量
- 滑动 200–450 ms（默认 300 ms），**直线、最短路径、匀速分段**（每 10 ms 一份报告）
- 逐键间隔 30–80 ms（默认 50 ms），每键按下固定 20 ms

**禁止——反检测 / 伪装真人，本固件一律不实现，以后也不要加：**
- 落点随机偏移（如 ±3–8 px）——始终命中给定坐标
- 无规律抖动、微震动
- 曲线/弧线轨迹、为“像真人”设计的加减速
- “想事情”式停顿
- 按应用类型改时序
- 词与词之间的拟人停顿

应用提示环境异常或拒绝自动输入时，手机端立即停止并如实汇报；不换输入方式、不改时序、不加抖动再试。

### 绝对坐标 → 相对鼠标（默认路径）

标准 HID 鼠标只有相对位移，固件这样换算：
1. 光标位置未知时（刚连上、取消后、快速拖动后）先 **HOME**：连发 25 份 (-2000, -2000)，把光标推到左上角 (0,0)。
2. 目标位移 = (目标像素 − 估计位置) × `counts_per_px_milli / 1000`，按 `max_step`（默认 4 计数/10 ms ≈ 400 计数/秒）**低速直线**移动，
   尽量避开 Android 的指针加速（低速时增益约为常数）。代价是长距离定位要 1–3 秒。
3. 到位后固定等待 20 ms，再按下/松开。
4. 鼠标拖动式滑动在 200–450 ms 内完成，速度较快，可能触发指针加速导致终点偏差；因此滑动后标记位置未知，下次先 HOME。

`counts_per_px_milli` 需要手机端标定（例如前台服务在自己的全屏 Activity 里读取鼠标悬停事件坐标，比较发出的计数与实际像素位移）。
系统「指针速度」设置会改变这个系数。**以上换算方法尚未在 AQM-AL10 上验证。**

### 可选：绝对触摸屏（Digitizer）

`CONFIG_HAND_ENABLE_DIGITIZER=y` 时报告描述符多一个单点 Touch Screen（Report ID 4），绝对坐标点击/长按/滑动改走触摸报告
（坐标按 SET_SCREEN 的宽高映射到 0..32767），无需 HOME 和换算。是否会被这台手机识别成触摸屏（而不是触控板/被忽略）**必须真机验证**。
改这个选项后需在手机上删除配对并重新配对（报告描述符变了）。

### 中文输入

HID 键盘只能按美式布局打 ASCII。中文内容建议手机端先放进剪贴板，再发 `KEY 01 19`（Ctrl+V）粘贴，是否可行以真机为准；
固件不会尝试用拼音模拟输入。

## 9. 必须在真机（荣耀 AQM-AL10）上验证的事项

1. 系统能否配对 `S3 Phone Hand` 并识别为键盘 + 鼠标；鼠标光标是否出现。
2. **鼠标是相对的**：每计数移动多少像素、低速下是否线性、指针速度设置的影响；HOME（推到左上角）是否可靠。
3. 点击 90 ms、长按 750 ms、滑动 300 ms 拖动在常见界面（列表、按钮、输入框）是否都被识别；鼠标拖动能否滚动列表。
4. 打字：50 ms 逐键是否丢字；接了物理键盘后系统输入法的行为（荣耀可能切换/隐藏软键盘）；Ctrl+V 粘贴是否可用。
5. Consumer Home/Back 是否生效。
6. **系统把它绑定成 HID 键鼠之后，手机 App 是否仍能 `connectGatt` 并访问自定义服务 `950f0001-…`**（Android 会对 App 隐藏 0x1812，
   其它服务一般可见，但必须实测）。做不到就记为限制，不设计绕过，也不退回用无障碍点击。
7. 鸿蒙休眠 / App 退到后台后，HID 连接和自定义 GATT 是否保持；断开后系统是否自动重连已绑定的 HID 设备。只用系统设置放行，不做保活漏洞。
8. 可选 Digitizer 是否被识别为触摸屏。
9. 外接天线情况下家里的 BLE 距离是否够用。

## 10. 不做的事

不刷手机、不 root、不伪装、不反检测、不做随机偏移/抖动/曲线；不在 BLE 上传 Key、截图、聊天记录；不实现音频；
不绕过鸿蒙的蓝牙后台、自启动等限制。本固件只在电脑上编译，**未烧录、未在手机上验证**。

## 11. Wi-Fi 局域网透传（v1.1.0）

用途：调试 / 控制。家里的 Mac 通过局域网把**和 GATT Action 一模一样的动作包**发给 ESP32，ESP32 用 BLE HID 在手机上执行。
手机端 App 走 GATT 的路径不变，两条通道共用同一个执行器（一次一条、不排队）。

### 11.1 设计要点

- 只做 **Wi-Fi STA**（不开 SoftAP），与 BLE 走 ESP-IDF 软件共存（`CONFIG_ESP_COEX_SW_COEXIST_ENABLE`），Wi-Fi 保持 modem sleep（共存必须）。
- **Wi-Fi 密码不在源码、sdkconfig、README、预编译镜像里。** 只能在运行时通过串口设置，存进 NVS 命名空间 `hand_wifi`（键 `ssid`、`pass`）；
  Wi-Fi 驱动自己的 NVS 存储已关闭（`WIFI_STORAGE_RAM`），密码只存这一份。串口任何时候都不回显、不打印密码。
- 上电时 NVS 里有凭据就自动连接；没有凭据或连不上，**BLE 照常工作**；断线后每 **20 s** 重试一次。
- 拿到 IP 会在串口打印：`GOT IP 192.168.x.y  →  TCP 3333 端口已就绪（mDNS: s3-phone-hand.local）`。
- DHCP 主机名与 mDNS 主机名都是 **`s3-phone-hand`**，Mac 上可直接用 `s3-phone-hand.local`；另外广播 mDNS 服务 `_s3hand._tcp`。
- TCP 服务器 `0.0.0.0:3333`，**同一时间只服务一个客户端**（后来的排队等前一个断开）。
- 动作执行中 TCP 客户端断开 → 固件自动 `CANCEL`（防止无人看管时还在点）。
- ⚠️ TCP 通道**没有加密和认证**，局域网里任何设备都能连。只在自己家的路由器下用，不要做端口映射到公网。
- NVS 分区与 BLE 绑定共用，但命名空间不同；`wifi_clear` 只删 Wi-Fi 凭据，不影响手机绑定。按 BOOT ≥ 8 s 清绑定也不会删 Wi-Fi 凭据。

### 11.2 用串口设置 Wi-Fi（不用重新编译）

用 Type-C 接板子的 **UART 口（CH343）**，115200 8N1。例如 `idf.py -p /dev/cu.wchusbserialXXXX monitor`，或 `screen /dev/cu.wchusbserialXXXX 115200`。
固件**不回显**你敲的字符（防止密码进串口记录），每条命令后打印一行确认。

| 命令 | 说明 |
|---|---|
| `wifi_set <ssid> <password>` | 一行设置。含空格/特殊字符用 `"双引号"`（内部 `\"`、`\\` 转义）或 `'单引号'` 包住 |
| `wifi_set` | 然后**下一行**输入 SSID，**再下一行**输入密码——整行原样，不做任何解析，最适合带奇怪字符的密码 |
| `wifi_set <ssid>` | 然后下一行输入密码（整行原样） |
| `wifi_status` | 打印 SSID、状态（`no_credentials` / `disconnected_retrying` / `associated_no_ip` / `connected`）、IP、RSSI、TCP 客户端；**不打印密码** |
| `wifi_clear` | 删除保存的凭据并断开 |
| `help` | 命令列表 |

示例（`<密码>` 换成路由器密码，**不要**把真密码写进任何脚本或文档）：

```
wifi_set
TP-LINK_758E
<密码>
```

成功后串口显示 `OK wifi_set ssid="TP-LINK_758E" password=******** saved to NVS, connecting...`，几秒后出现 `GOT IP ...`。
`wifi_set` 后立刻保存并重新连接；以后重启自动连。SSID 1–32 字节，密码 8–64 字节（开放网络留空）。

用脚本设置时也一样：用 pyserial 逐行写 `wifi_set\r\n`、`SSID\r\n`、`密码\r\n`，密码从环境变量/钥匙串读，不要落盘。

### 11.3 TCP 帧格式（全部小端）

**请求**：`[u16 len][len 字节动作包]`，动作包 = 第 7 节的 `[u8 opcode][u8 seq][payload...]`，最长 256 字节。`len=0` 的空帧被当作保活忽略。

**回复**：`[u16 len][u8 seq][u8 result][u8 opcode][u8 phase][附加数据...]`

| phase | 含义 |
|---|---|
| 0 | 提交结果，立刻返回：`ACCEPTED`(8) 表示已开始执行；`OK`(0) 用于 CANCEL / SET_SCREEN / STATUS；其它为错误码（BUSY、BAD_PARAM、NOT_READY…） |
| 1 | 执行完的最终结果（**仅当 phase 0 为 ACCEPTED 时**才有）：OK / CANCELLED / HID_ERROR… |

规则与 GATT 完全相同：一次一条，不排队（忙时 `BUSY`）；**时长越界一律 `BAD_PARAM`，不夹紧**；`CANCEL`(0x7F) 永远立即生效。
GATT 和 TCP 谁先提交谁执行；TCP 提交的动作也会照常更新 GATT Status（`last_seq/last_result`）。

**仅 TCP 的查询**：opcode `0x40` STATUS（无参数），回复 phase 0、result OK，附加 12 字节 Status（格式同第 7 节）。
在 GATT 上发 0x40 返回 `UNSUPPORTED`。

例：Ctrl+V，seq=5
```
请求  06 00 | 21 05 01 19 00 00
回复  04 00 | 05 08 21 00        ← ACCEPTED
回复  04 00 | 05 00 21 01        ← 最终 OK
```

### 11.4 Mac 上的客户端 `tools/wifi_hand.py`

只依赖 Python 3 标准库（打包复用 `tools/hand_proto.py`），脚本里没有任何凭据。每次连一下、发一条、等最终结果、断开。

```bash
python3 tools/wifi_hand.py --host s3-phone-hand.local status          # 读状态
python3 tools/wifi_hand.py --host 192.168.0.123 set-screen 1080 2400  # AQM-AL10 屏幕
python3 tools/wifi_hand.py --host 192.168.0.123 home
python3 tools/wifi_hand.py --host 192.168.0.123 click 540 1200
python3 tools/wifi_hand.py swipe 540 1800 540 600 --dur 300
python3 tools/wifi_hand.py type "hello"
python3 tools/wifi_hand.py key 0x01 0x19      # Ctrl+V（中文先放剪贴板再粘贴）
python3 tools/wifi_hand.py consumer 0x0224    # 返回
python3 tools/wifi_hand.py cancel
python3 tools/wifi_hand.py raw 210501190000    # 直接发十六进制动作包
```

`--host` 也可用环境变量 `HAND_HOST`（默认 `s3-phone-hand.local`），端口 `HAND_PORT`（默认 3333）。
退出码：最终结果为 OK 时 0，其它结果 1，连接失败 2。

### 11.5 需要真机验证

1. Wi-Fi + BLE 共存时 HID 点击 / 滑动 / 打字的时序是否仍稳定（共存会让 BLE 偶尔让出射频）。
2. 路由器 `TP-LINK_758E` 是 2.4 GHz（ESP32-S3 不支持 5 GHz）；加密方式 WPA/WPA2/WPA3 均可。
3. `s3-phone-hand.local` 在 Mac 上能否解析；不行就用串口 `wifi_status` 看到的 IP，最好在路由器里给它绑定固定 IP。

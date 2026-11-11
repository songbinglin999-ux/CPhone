# CPhone

CPhone 是家里备用机助手：Android 应用「远手」，加上 ESP32-S3 的 BLE HID / Wi-Fi 透传固件。远手在备用手机上编排任务；ESP32-S3 以蓝牙键鼠把点击、滑动和打字交给这台手机，也可以在家里 Wi-Fi 上用 TCP 透传同一套动作。

- `yuanhou-hand/`：Android 应用「远手」（主屏操作、微信与局域网通道、确认闸）。付款不会自动执行。
- `s3-ble-hid-hand/`：ESP32-S3 固件（BLE HID 键鼠，以及家里局域网里的 Wi-Fi TCP 透传）。

两个子目录里的 README 分别说明怎么编译应用和固件。局域网服务只绑在家里的私有网段上使用，不要做端口转发，不要暴露到公网。

API Key、Wi-Fi 密码、局域网口令、华为或手机密码，以及签名用的 keystore，都不进本仓库，只在各自设备本机配置。

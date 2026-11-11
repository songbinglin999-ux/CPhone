# 预编译镜像 v1.1.0（默认配置：LED GPIO48、BOOT GPIO0、无 Digitizer；BLE HID + Wi-Fi TCP 透传；ESP-IDF v5.3.2）
编译 0 警告 0 错误；**未烧录、未真机验证 Wi-Fi/BLE 共存**。镜像里不含任何 Wi-Fi 凭据（烧完用串口 `wifi_set` 设置，见 README 第 11 节）。
校验：`shasum -a 256 -c SHA256SUMS`（在本目录上一级执行，或去掉路径前缀）。

分区表与 1.0.0 相同（nvs 0x9000 / phy_init 0xf000 / factory 0x10000 3MB）。**不要 erase_flash**：NVS 里存着手机的 BLE 绑定，
擦掉就得在手机上删配对重新配。下面的命令只写 bootloader / 分区表 / app，不碰 NVS，已配对的手机不用重配。

完整烧录（由你自己执行，会覆盖板上原固件）：
    python -m esptool --chip esp32s3 -p PORT -b 460800 --before default_reset --after hard_reset \
      write_flash --flash_mode dio --flash_size 16MB --flash_freq 80m \
      0x0 bootloader.bin 0x8000 partition-table.bin 0x10000 s3_phone_hand.bin

只升级 app（从 1.0.0 升上来时就够了）：
    python -m esptool --chip esp32s3 -p PORT -b 460800 --before default_reset --after hard_reset \
      write_flash --flash_mode dio --flash_size 16MB --flash_freq 80m 0x10000 s3_phone_hand.bin

回退：`v1.0.0/` 目录里是上一版（纯 BLE）的三个镜像和它们的 SHA256SUMS，同样的命令烧回即可。

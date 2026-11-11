package com.yuanhou.hand.port;

/**
 * MainScreenEsp32Port 用到的「手」：全部经 ESP32-S3（固件 1.1.0，Wi-Fi 透传 s3-phone-hand.local:3333，GATT 备用）
 * 以 BLE HID 注回主屏。阻塞式，只在工作线程调用。坐标是主屏真实像素（1080×2400）。
 * 实现方（Esp32HandAdapter）不得退回无障碍点击/手势，也不加抖动、弧线或落点偏移。
 */
public interface HandPort {
    /** null = 就绪；否则是没就绪的原因（链路、SET_SCREEN……）。 */
    String notReadyReason();

    int screenW();

    int screenH();

    void click(int x, int y) throws PortException, InterruptedException;

    void longPress(int x, int y) throws PortException, InterruptedException;

    /** 直线拖动（固件 move_straight）。 */
    void swipe(int x1, int y1, int x2, int y2) throws PortException, InterruptedException;

    /** 光标移到 (x,y) 后发滚轮；notches&gt;0 内容往下翻。 */
    void scroll(int x, int y, int notches) throws PortException, InterruptedException;

    /** Consumer AC Back。 */
    void back() throws PortException, InterruptedException;

    /** Consumer AC Home。 */
    void home() throws PortException, InterruptedException;

    /** 键盘回车（usage 0x28）。是不是「发出」由确认闸判断，这里只执行。 */
    void enter() throws PortException, InterruptedException;

    /** 键盘 Esc（usage 0x29）。 */
    void escape() throws PortException, InterruptedException;

    /** Ctrl+End：光标到整段文末。这台机的百度输入法会吞右方向键，所以只用这个。 */
    void cursorToEnd() throws PortException, InterruptedException;

    /** Backspace 按 n 下（有上限）。不用 Ctrl+A。 */
    void backspaces(int n) throws PortException, InterruptedException;

    /** 写本应用剪贴板 → HID Ctrl+V。中英文都走这里（百度输入法把 Shift 当中英切换，不逐键打字）。 */
    void paste(String text) throws PortException, InterruptedException;
}

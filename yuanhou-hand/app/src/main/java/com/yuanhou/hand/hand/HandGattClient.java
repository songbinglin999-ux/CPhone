package com.yuanhou.hand.hand;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Set;
import java.util.UUID;

/**
 * 连到已绑定的 S3 Phone Hand，访问自定义 GATT 服务（不扫描 HID 服务 0x1812）。
 * 这正是要在真机上验证的关键：系统已把它当键鼠之后，App 还能不能 connectGatt。
 */
public final class HandGattClient {
    public static final String TAG = "YuanHand";
    public static final String DEVICE_NAME = "S3 Phone Hand";

    public interface Listener {
        void onLog(String line);
        void onConnectionChanged(boolean connected, String detail);
        void onServices(boolean customServiceFound, String info);
        void onStatus(HandProtocol.Status status, boolean fromNotify);
        void onActionAck(int seq, int opcode, int result);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic actionCh;
    private BluetoothGattCharacteristic statusCh;
    private BluetoothGattCharacteristic ledCh;
    private BluetoothGattCharacteristic infoCh;
    private boolean ready;
    private int currentMtu = 23;
    private int connectGen;
    private boolean discoveryStarted;
    private boolean notifyArmed;
    private boolean servicesAnnounced;
    private Runnable mtuDiscoverTask;
    private int notifyLogLeft = 8;

    public HandGattClient(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean hasGatt() {
        return gatt != null;
    }

    /** 主动读一次 Status（通知没到时用来确认探头还在）。只在没有动作在途时调用。 */
    public boolean readStatus() {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic ch = statusCh;
        if (!ready || g == null || ch == null) return false;
        try {
            return g.readCharacteristic(ch);
        } catch (SecurityException e) {
            return false;
        }
    }

    public int getMtu() {
        return currentMtu;
    }

    public void connect() {
        close();
        final int gen = ++connectGen;
        discoveryStarted = false;
        notifyArmed = false;
        servicesAnnounced = false;
        notifyLogLeft = 8;
        if (mtuDiscoverTask != null) {
            main.removeCallbacks(mtuDiscoverTask);
            mtuDiscoverTask = null;
        }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onConnectionChanged(false, "系统蓝牙未打开");
            return;
        }
        BluetoothDevice target = null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (DEVICE_NAME.equals(d.getName())) {
                        target = d;
                        break;
                    }
                }
            }
        } catch (SecurityException e) {
            listener.onConnectionChanged(false, "没有蓝牙权限: " + e.getMessage());
            return;
        }
        if (target == null) {
            listener.onConnectionChanged(false, "已配对列表里没有 “" + DEVICE_NAME + "”");
            return;
        }
        String addr = target.getAddress();
        String masked = (addr != null && addr.length() >= 8)
                ? addr.substring(0, 8) + "**:**:**" : "?";
        log("开始 connectGatt，地址 " + masked + "，TRANSPORT_LE");
        try {
            gatt = target.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            listener.onConnectionChanged(false, "connectGatt 被拒绝: " + e.getMessage());
            return;
        }
        if (gatt == null) {
            listener.onConnectionChanged(false, "connectGatt 返回 null");
        }
    }

    public void close() {
        connectGen++;
        ready = false;
        discoveryStarted = false;
        notifyArmed = false;
        servicesAnnounced = false;
        if (mtuDiscoverTask != null) {
            main.removeCallbacks(mtuDiscoverTask);
            mtuDiscoverTask = null;
        }
        actionCh = statusCh = ledCh = infoCh = null;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (SecurityException ignored) {}
            try {
                g.close();
            } catch (SecurityException ignored) {}
        }
    }

    /** 写入 Action，成功排队后由 Status 通知带回 ACCEPTED 和最终结果。 */
    public boolean writeAction(byte[] payload) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic ch = actionCh;
        if (!ready || g == null || ch == null) {
            log("还不能发指令：GATT 未就绪");
            return false;
        }
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        ch.setValue(payload);
        try {
            boolean ok = g.writeCharacteristic(ch);
            if (!ok) log("writeCharacteristic 返回 false");
            return ok;
        } catch (SecurityException e) {
            log("写入 Action 被拒绝: " + e.getMessage());
            return false;
        }
    }

    public boolean writeLed(int state) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic ch = ledCh;
        if (g == null || ch == null) return false;
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        ch.setValue(new byte[]{(byte) state});
        try {
            return g.writeCharacteristic(ch);
        } catch (SecurityException e) {
            return false;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            String st = status == BluetoothGatt.GATT_SUCCESS ? "SUCCESS" : ("GATT_STATUS_" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT 已连接 (" + st + ")，请求 MTU 517");
                post(() -> listener.onConnectionChanged(true, "已连接，正在发现服务"));
                try {
                    boolean mtuOk = g.requestMtu(517);
                    if (!mtuOk) {
                        log("requestMtu 失败，直接发现服务");
                        scheduleDiscovery(g);
                    } else {
                        final int captured = connectGen;
                        main.postDelayed(() -> {
                            if (connectGen == captured && !discoveryStarted && gatt == g) {
                                log("等待 MTU 超时，改走 discoverServices");
                                scheduleDiscovery(g);
                            }
                        }, 4000);
                    }
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (SecurityException e) {
                    log("连接后操作被拒绝: " + e.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                log("GATT 断开 (" + st + ")");
                post(() -> listener.onConnectionChanged(false, "已断开 " + st));
                try { g.close(); } catch (SecurityException ignored) {}
                if (gatt == g) gatt = null;
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            currentMtu = mtu;
            log("MTU=" + mtu + " status=" + status);
            // 荣耀会连续回两次 MTU，第二次会冲掉尚未完成的 CCCD 回调，先等它稳定
            scheduleDiscovery(g);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            StringBuilder dump = new StringBuilder();
            dump.append("服务列表 status=").append(status).append("\n");
            BluetoothGattService custom = null;
            try {
                for (BluetoothGattService s : g.getServices()) {
                    UUID u = s.getUuid();
                    dump.append("  ").append(u).append(" chars=").append(s.getCharacteristics().size()).append("\n");
                    if (HandProtocol.SVC.equals(u)) custom = s;
                }
            } catch (SecurityException e) {
                post(() -> listener.onServices(false, "读取服务被拒绝: " + e.getMessage()));
                return;
            }
            log(dump.toString().trim());
            if (custom == null) {
                post(() -> listener.onServices(false,
                        "自定义服务 950f0001-… 不可见。这记为限制：系统占用键鼠后，应用连不上指令通道。"));
                return;
            }
            actionCh = custom.getCharacteristic(HandProtocol.ACTION);
            statusCh = custom.getCharacteristic(HandProtocol.STATUS);
            ledCh = custom.getCharacteristic(HandProtocol.LED);
            infoCh = custom.getCharacteristic(HandProtocol.INFO);
            if (actionCh == null || statusCh == null) {
                post(() -> listener.onServices(false, "自定义服务在，但缺少 Action/Status 特征"));
                return;
            }
            if (notifyArmed) {
                log("通知已在设置，忽略重复的服务发现");
                return;
            }
            enableNotify(g, statusCh);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            log("CCCD 写入 status=" + status + " uuid=" + d.getUuid());
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("CCCD 失败，仍尝试直接使用通道");
                markChannelOpen(g, "CCCD status=" + status + "，尝试直接使用通道");
                return;
            }
            markChannelOpen(g, "CCCD 已打开");
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("读取失败 uuid=" + ch.getUuid() + " status=" + status);
                if (HandProtocol.INFO.equals(ch.getUuid())) {
                    announceServices("指令通道已打开（Info 读取失败）");
                }
                return;
            }
            if (HandProtocol.INFO.equals(ch.getUuid())) {
                String info = new String(ch.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                log("Info: " + info);
                announceServices(info);
            } else if (HandProtocol.STATUS.equals(ch.getUuid())) {
                deliverStatus(ch.getValue(), false);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            UUID u = ch.getUuid();
            byte[] v = ch.getValue();
            if (notifyLogLeft > 0) {
                notifyLogLeft--;
                log("通知 uuid=" + u + " len=" + (v == null ? -1 : v.length));
            }
            if (HandProtocol.STATUS.equals(u)) {
                if (!ready) markChannelOpen(g, "收到 Status 通知");
                deliverStatus(v, true);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (HandProtocol.ACTION.equals(ch.getUuid())) {
                log("Action 写入 GATT status=" + status);
            }
        }
    };

    private void scheduleDiscovery(BluetoothGatt g) {
        if (g == null || discoveryStarted) return;
        if (mtuDiscoverTask != null) main.removeCallbacks(mtuDiscoverTask);
        final BluetoothGatt gg = g;
        mtuDiscoverTask = () -> {
            mtuDiscoverTask = null;
            startDiscovery(gg);
        };
        main.postDelayed(mtuDiscoverTask, 400);
    }

    private void markChannelOpen(BluetoothGatt g, String why) {
        if (ready && servicesAnnounced) return;
        ready = true;
        log("指令通道就绪: " + why);
        if (infoCh != null && g != null && !servicesAnnounced) {
            try {
                boolean ok = g.readCharacteristic(infoCh);
                log("read Info=" + ok);
                if (!ok) announceServices(why);
                return;
            } catch (SecurityException e) {
                announceServices(why + "（读 Info 被拒绝）");
                return;
            }
        }
        announceServices(why);
    }

    private void announceServices(String info) {
        if (servicesAnnounced) return;
        servicesAnnounced = true;
        ready = true;
        post(() -> listener.onServices(true, info));
    }

    private void startDiscovery(BluetoothGatt g) {
        if (g == null || discoveryStarted) return;
        discoveryStarted = true;
        try {
            boolean ok = g.discoverServices();
            log("discoverServices=" + ok);
            if (!ok) {
                discoveryStarted = false;
                log("discoverServices 返回 false");
            }
        } catch (SecurityException e) {
            discoveryStarted = false;
            log("discoverServices 被拒绝: " + e.getMessage());
        }
    }

    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        notifyArmed = true;
        try {
            boolean ok = g.setCharacteristicNotification(ch, true);
            log("setCharacteristicNotification=" + ok);
            BluetoothGattDescriptor cccd = ch.getDescriptor(HandProtocol.CCCD);
            if (cccd == null) {
                markChannelOpen(g, "指令通道已打开（无 CCCD，可能收不到通知）");
                return;
            }
            writeCccd(g, cccd, 0);
        } catch (SecurityException e) {
            post(() -> listener.onServices(false, "打开通知被拒绝: " + e.getMessage()));
        }
    }

    private void writeCccd(BluetoothGatt g, BluetoothGattDescriptor cccd, int attempt) {
        if (g == null || gatt != g) return;
        try {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean w = g.writeDescriptor(cccd);
            log("writeDescriptor CCCD=" + w + " attempt=" + (attempt + 1));
            if (!w) {
                if (attempt < 4) {
                    main.postDelayed(() -> writeCccd(g, cccd, attempt + 1), 300);
                } else {
                    log("CCCD 写入一直失败，改为直接使用通道");
                    markChannelOpen(g, "CCCD 写入失败，尝试直接使用通道");
                }
            } else {
                final int captured = connectGen;
                main.postDelayed(() -> {
                    if (connectGen == captured && !servicesAnnounced && gatt == g) {
                        log("CCCD 回调未到，改为直接使用通道");
                        markChannelOpen(g, "CCCD 回调超时，尝试直接使用通道");
                    }
                }, 2000);
            }
        } catch (SecurityException e) {
            post(() -> listener.onServices(false, "写入 CCCD 被拒绝: " + e.getMessage()));
        }
    }

    private void deliverStatus(byte[] value, boolean notify) {
        try {
            HandProtocol.Status s = new HandProtocol.Status(value);
            post(() -> {
                listener.onStatus(s, notify);
                if (notify) listener.onActionAck(s.lastSeq, s.lastOpcode, s.lastResult);
            });
        } catch (IllegalArgumentException e) {
            log("Status 解析失败 len=" + (value == null ? -1 : value.length));
        }
    }

    private void log(String line) {
        // logcat 由 AppLog 统一写，避免重复
        post(() -> listener.onLog(line));
    }

    private void post(Runnable r) {
        main.post(r);
    }
}

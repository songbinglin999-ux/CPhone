package com.yuanhou.hand.policy;

import com.yuanhou.hand.screen.ScreenSnapshot;

/**
 * 广告和意外跳转由代码处理，不问模型。模型看见红包弹窗时常常会去点，那正是广告想要的。
 * 放在每次读屏之后、问模型之前。关弹窗只点「跳过 / 关闭」这一类白名单，走 ESP32 HID，不过确认闸。
 * 不点领取、开红包、摇一摇。没有关闭按钮的全屏广告只等，不用截图去找叉。
 */
public final class InterruptionGuard {
    public static final int CONSECUTIVE_POPUP_CAP = 3;
    public static final int TASK_POPUP_CAP = 5;
    public static final int FULLSCREEN_WAIT_SLICES = 3;

    /** 可以点的关闭/跳过。必须整段相等，并且可点。 */
    static final String[] CLOSE_WORDS = {
            "跳过", "跳过广告", "关闭广告", "关闭弹窗",
            "关闭", "我知道了", "暂不", "暂不领取", "以后再说",
            "残忍拒绝", "残忍离开", "不再提示", "不感兴趣"
    };
    /** 只用来认出广告，永远不作为点击目标。 */
    static final String[] CLAIM_WORDS = {
            "开红包", "立即领取", "领取红包", "摇一摇", "拆红包", "去领取",
            "马上抢", "立即抢", "领券", "点击领取", "立即打开", "开抢", "去逛逛"
    };
    static final String[] COUNTDOWN_WORDS = {
            "广告倒计时", "秒后可关闭", "秒后关闭", "秒后可跳过", "秒后跳过", "广告剩余"
    };
    static final String[] CAMPAIGN_WORDS = {
            "直播间", "正在直播", "进入直播", "活动会场", "主会场", "红包雨", "超级红包", "限时疯抢"
    };
    static final String[] FEED_WORDS = {"猜你喜欢", "为你推荐", "首页"};

    public enum Action { CLEAR, TAP_CLOSE, BACK, HOME, REENTER, WAIT, STOP }

    public static final class Decision {
        public final Action action;
        /** 只来自这一屏；上一屏的编号不能拿来点。 */
        public final ScreenSnapshot.Node target;
        public final String speak;
        public final boolean needsHuman;
        public final boolean invalidateRefs;
        public final String memoryApp;
        public final boolean homeFeed;
        /** BACK 发生在已经离开目标应用时为 true；应用内跳转的那一次返回为 false。 */
        public final boolean foreign;

        Decision(Action action, ScreenSnapshot.Node target, String speak, boolean needsHuman,
                 boolean invalidateRefs, String memoryApp, boolean homeFeed, boolean foreign) {
            this.action = action;
            this.target = target;
            this.speak = speak == null ? "" : speak;
            this.needsHuman = needsHuman;
            this.invalidateRefs = invalidateRefs;
            this.memoryApp = memoryApp == null ? "" : memoryApp;
            this.homeFeed = homeFeed;
            this.foreign = foreign;
        }
    }

    private String expectedPackage = "";
    private String expectedTitle = "";
    private long expectedFp;
    private boolean haveExpected;
    private String memoryApp = "";
    private boolean expectedFeed;
    private int consecutive;
    private int total;
    private int foreignTries;
    private boolean backedFromJump;
    private boolean reentered;
    private boolean waitedFullscreen;
    private boolean closeUnusable;
    private String entryHint = "";

    public void setEntryHint(String hint) { entryHint = hint == null ? "" : hint; }

    public String expectedPackage() { return expectedPackage; }

    public String entryHint() { return entryHint; }

    public int consecutivePopups() { return consecutive; }

    public int totalPopups() { return total; }

    public boolean overCap() {
        return consecutive >= CONSECUTIVE_POPUP_CAP || total >= TASK_POPUP_CAP;
    }

    /** 这一屏可以交给模型时记下，作为下一屏要回来的页面。 */
    public void accept(ScreenSnapshot snap) {
        if (snap == null) return;
        expectedPackage = snap.packageName == null ? "" : snap.packageName;
        expectedTitle = titleBar(snap);
        expectedFp = snap.fingerprint();
        haveExpected = !expectedPackage.isEmpty();
        String shown = UntilCheck.displayName(expectedPackage);
        if (shown != null) memoryApp = shown;
        expectedFeed = looksLikeFeed(snap);
        consecutive = 0;
        foreignTries = 0;
        backedFromJump = false;
        reentered = false;
        waitedFullscreen = false;
        closeUnusable = false;
    }

    public static String popupNote(int step) {
        return "第 " + step + " 步关掉红包弹窗";
    }

    /**
     * 包名或指纹和这一批开始时的那一屏不同：这一批里剩下的编号全部作废。
     */
    public static boolean refsStale(String plannedPkg, long plannedFp, ScreenSnapshot now) {
        if (now == null) return true;
        String pkg = now.packageName == null ? "" : now.packageName;
        String was = plannedPkg == null ? "" : plannedPkg;
        if (!was.isEmpty() && !was.equals(pkg)) return true;
        return now.fingerprint() != plannedFp;
    }

    /** 只读判断，不改计数。执行成功后再 {@link #commit}。 */
    public Decision assess(ScreenSnapshot snap) {
        if (snap == null) {
            return stop("读不到界面，先停下。", false);
        }
        SafetyGuard.Verdict safety = SafetyGuard.check(snap);
        if (safety.stop()) {
            return stop("停下了：" + safety.reason + "。这一步需要你本人处理，我不会继续自动输入。", true);
        }
        boolean feed = expectedFeed || looksLikeFeed(snap);
        String app = memoryApp.isEmpty() ? UntilCheck.displayName(snap.packageName) : memoryApp;
        if (app == null) app = "";

        if (leftApp(snap)) {
            if (foreignTries >= 2) {
                return stop("离开了正在办的应用，返回和回主页都没回来，先停下。没有在那个页面上点任何东西。", false);
            }
            Action nav = foreignTries == 0 ? Action.BACK : Action.HOME;
            return new Decision(nav, null, "", false, true, app, feed, true);
        }

        ScreenSnapshot.Node close = closeUnusable ? null : findClose(snap);
        boolean claim = containsAny(snap, CLAIM_WORDS);
        boolean countdown = containsAny(snap, COUNTDOWN_WORDS);
        boolean adWord = contains(snap, "广告") || contains(snap, "红包");
        boolean skipOnly = close != null && isSkipLabel(close.label());
        boolean popup = close != null && (claim || countdown || adWord || skipOnly || containsAny(snap, CAMPAIGN_WORDS));
        if (popup) {
            if (overCap()) return stop(capSpeech(), false);
            return new Decision(Action.TAP_CLOSE, close, "", false, true, app, feed, false);
        }
        if (fullscreen(snap, close) || (claim && close == null)) {
            if (waitedFullscreen) {
                String why = claim && close == null
                        ? "屏上是领取或开红包，没有可关的按钮。需要你本人处理，我不会去点领取。"
                        : "这页广告没有可关的按钮，等了一会儿还在，需要你本人处理。我不会在倒计时结束前乱点。";
                return stop(why, true);
            }
            return new Decision(Action.WAIT, null, "", false, true, app, feed, false);
        }
        if (inAppJump(snap)) {
            if (!backedFromJump) return new Decision(Action.BACK, null, "", false, true, app, feed, false);
            if (!reentered) return new Decision(Action.REENTER, null, "", false, true, app, feed, false);
            return stop("跳进了活动页，返回一次并按进入路线重开都没回到原来的页面，先停下。没有在广告页里继续点。", false);
        }
        return new Decision(Action.CLEAR, null, "", false, false, "", false, false);
    }

    public void commit(Decision d) {
        if (d == null) return;
        switch (d.action) {
            case TAP_CLOSE:
                consecutive++;
                total++;
                break;
            case BACK:
                if (d.foreign) foreignTries++;
                else backedFromJump = true;
                break;
            case HOME:
                foreignTries++;
                break;
            case REENTER:
                reentered = true;
                break;
            case WAIT:
                waitedFullscreen = true;
                break;
            default:
                break;
        }
    }

    /** 关不了（点不到安全区域）时，下一轮改成等待，不去点领取。 */
    public void markCloseUnusable() { closeUnusable = true; }

    /**
     * 重新进入时可以点的入口：搜索、我的、订单。广告词和领取按钮不会返回。
     * 当前屏仍是广告或活动页时返回 null，调用方不得在那一页上点。
     */
    public static ScreenSnapshot.Node entryNode(ScreenSnapshot snap, String entryHint) {
        if (snap == null) return null;
        if (containsAny(snap, CLAIM_WORDS) || containsAny(snap, COUNTDOWN_WORDS) || containsAny(snap, CAMPAIGN_WORDS)) {
            return null;
        }
        if (SafetyGuard.check(snap).stop()) return null;
        String hint = entryHint == null ? "" : entryHint;
        String[] order = hint.contains("我的") || hint.contains("订单")
                ? new String[]{"我的", "订单", "搜索"}
                : new String[]{"搜索", "我的", "订单"};
        for (String word : order) {
            ScreenSnapshot.Node n = snap.findByText(word);
            if (n == null || !n.clickable || n.password) continue;
            String lab = n.label() == null ? "" : n.label().trim();
            if (isClaimLabel(lab) || lab.length() > 8) continue;
            return n;
        }
        return null;
    }

    private boolean leftApp(ScreenSnapshot snap) {
        if (!haveExpected || expectedPackage.isEmpty()) return false;
        String pkg = snap.packageName == null ? "" : snap.packageName;
        return !pkg.isEmpty() && !pkg.equals(expectedPackage);
    }

    private boolean inAppJump(ScreenSnapshot snap) {
        if (!haveExpected) return false;
        String pkg = snap.packageName == null ? "" : snap.packageName;
        if (!pkg.equals(expectedPackage)) return false;
        if (snap.fingerprint() == expectedFp) return false;
        String title = titleBar(snap);
        if (title.isEmpty() || title.equals(expectedTitle)) return false;
        return containsAny(snap, CAMPAIGN_WORDS);
    }

    private static boolean fullscreen(ScreenSnapshot snap, ScreenSnapshot.Node close) {
        if (close != null) return false;
        if (containsAny(snap, COUNTDOWN_WORDS)) return true;
        if (snap.readableCount() > 1) return false;
        for (ScreenSnapshot.Node n : snap.nodes) {
            String cls = n.cls == null ? "" : n.cls;
            if (cls.contains("WebView") || cls.contains("webview")) return true;
        }
        return false;
    }

    private String capSpeech() {
        if (consecutive >= CONSECUTIVE_POPUP_CAP) {
            return "红包弹窗一连关了 " + CONSECUTIVE_POPUP_CAP + " 次还在弹，先停下，没有继续点。";
        }
        return "这一趟已经关了 " + TASK_POPUP_CAP + " 次广告弹窗，先停下，没有继续点。";
    }

    private Decision stop(String speak, boolean needsHuman) {
        return new Decision(Action.STOP, null, speak, needsHuman, true, "", false, false);
    }

    static ScreenSnapshot.Node findClose(ScreenSnapshot snap) {
        if (snap == null) return null;
        for (String word : CLOSE_WORDS) {
            for (ScreenSnapshot.Node n : snap.nodes) {
                if (!n.clickable || !n.enabled || n.password) continue;
                String lab = n.label() == null ? "" : n.label().trim();
                if (!lab.equals(word) || isClaimLabel(lab)) continue;
                if (!isSkipLabel(lab) && !adContext(snap)) continue;
                return n;
            }
        }
        for (ScreenSnapshot.Node n : snap.nodes) {
            if (!n.clickable || !n.enabled || n.password) continue;
            String lab = n.label() == null ? "" : n.label().trim();
            if (!adContext(snap)) continue;
            if (lab.equals("×") || lab.equals("✕") || lab.equalsIgnoreCase("x")) return n;
        }
        return null;
    }

    private static boolean adContext(ScreenSnapshot snap) {
        return containsAny(snap, CLAIM_WORDS) || containsAny(snap, COUNTDOWN_WORDS)
                || containsAny(snap, CAMPAIGN_WORDS) || contains(snap, "广告") || contains(snap, "红包");
    }

    static boolean isSkipLabel(String lab) {
        if (lab == null) return false;
        String t = lab.trim();
        return "跳过".equals(t) || "跳过广告".equals(t) || "关闭广告".equals(t) || "关闭弹窗".equals(t);
    }

    static boolean isClaimLabel(String lab) {
        if (lab == null || lab.isEmpty()) return false;
        for (String w : CLAIM_WORDS) if (lab.contains(w)) return true;
        return false;
    }

    static boolean looksLikeFeed(ScreenSnapshot snap) {
        return containsAny(snap, FEED_WORDS);
    }

    static String titleBar(ScreenSnapshot snap) {
        if (snap == null) return "";
        StringBuilder b = new StringBuilder();
        if (snap.windowTitle != null) b.append(snap.windowTitle.trim());
        int band = snap.titleBandBottom();
        for (ScreenSnapshot.Node n : snap.nodes) {
            if (band <= 0 || n.bounds.bottom <= 0 || n.bounds.bottom > band) continue;
            String lab = n.label();
            if (lab == null || lab.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(lab.trim());
        }
        return b.toString().replaceAll("\\s+", "");
    }

    private static boolean contains(ScreenSnapshot snap, String word) {
        return containsAny(snap, new String[]{word});
    }

    private static boolean containsAny(ScreenSnapshot snap, String[] words) {
        if (snap == null || words == null) return false;
        if (snap.windowTitle != null) {
            for (String w : words) if (snap.windowTitle.contains(w)) return true;
        }
        for (ScreenSnapshot.Node n : snap.nodes) {
            String lab = n.label() == null ? "" : n.label();
            String hint = n.hint == null ? "" : n.hint;
            for (String w : words) {
                if (lab.contains(w) || hint.contains(w)) return true;
            }
        }
        return false;
    }
}

# 远手（com.yuanhou.hand）第一期前台编排应用 · 0.1.21

依据：/workspace/private-phone-assistant-phase1-ble.md。复用 s3-hand-test 已在真机跑通的 GATT 客户端。

## 编译 / 安装（桌上联调）
- Mac：/Users/mac/goji-tmp/s3-hand-orchestrator，`gradle assembleDebug`（Gradle 8.11.1，AGP 8.7.3，JDK 17，compileSdk 36，min/target 29）
- 签名：盒子打包前把 Mac 的 `~/.android/debug.keystore` 复制为工程里的 `signing/mac-debug.keystore`（已 gitignore，不要提交）。也可设置环境变量 `YUANHOU_DEBUG_KEYSTORE` 指向该文件，或在 Mac 上直接打包（会用 `/Users/mac/.android/debug.keystore`）。否则打出的 APK 用的是盒子自己的 debug 证书，和机上已装版本签名不兼容，无法直接 `-r` 升级，不要把它当作可升级包。
- 安装：`adb push app-debug.apk /data/local/tmp/ && adb shell pm install -r -d -g /data/local/tmp/yuanhou-hand.apk`（荣耀会弹「风险提示/继续安装」）
- 桌面自测：`adb shell am start -n com.yuanhou.hand/.ui.MainActivity --ez selftest true`（0.1.13 起首页会转到「设置 › 调试」，在那里点自测框），结果在
  /sdcard/Android/data/com.yuanhou.hand/files/selftest.txt，日志 log.txt
- 文字指令：`--es say "演练发微信 张三 你好"`（参数里不要有空格以外的 shell 特殊字符）

## 0.1.21：广告和跳转由代码处理

读完主屏、问模型之前先过安全闸，再过打断守卫。红包、领券、直播间和跳出当前应用不交给模型判断。

- 界面树上有「跳过 / 关闭广告」这类关闭，并且同时能看出是广告或红包时，本机用 ESP32 HID 点那个关闭，不过确认闸，也不改用无障碍点击。点完重读，确认回到原来的页面，并在步骤里写「第 N 步关掉红包弹窗」。一连关掉 3 个，或这一趟关掉 5 个，就停下并说明。
- 还在同一个应用里，但标题变成直播间或会场：只按一次返回。回不来就按技能里的进入路线重新打开（搜索，或「我的」「订单」），不在广告页里继续点。
- 已经离开原来的应用：外面的页面只允许返回和回主页，不点任何按钮。付款页仍由安全闸停下，交给本人。返回和回主页两次都没回来就停下。
- 全屏倒计时、或界面树读不出来的网页广告：只等，不用截图去找叉。等完还在，就以需要本人处理结束。
- 一次多步进行中，包名或界面指纹和这一批开始时不同，整批剩下的编号作废，后面不再点。
- 办成的技能会写明进入路线，不从首页信息流进。出现过弹窗会记进这个应用的经验，例如「淘宝从首页进弹窗多」。
- 淘宝摇一摇、个性化广告和推送开关由使用的人在手机上自己关，这一版不改那些系统设置。

## 0.1.20：长程里的摘要、记事、技能提纲、办完前核对

- 一次任务里的步骤变长以后，较早的步骤收成结构化摘要（第几步、什么动作、点的是什么或哪个应用、做成没有、当时界面），最近几步仍把原文交给模型。不再把开头裁掉并写「更早的步骤已省略」。每 15 步念的进度用这份摘要。
- 本机私有目录 `playbook/` 里记两类事，换任务、重启应用还在，不上传：用户原话里写明的偏好（常去的城市、习惯用的应用、确认时怎么说）；某个应用里做成过的路线和明确没做成的坑。下一件相关的事只把用得上的几条放进模型上下文。密钥、口令串、密码不记。设置页不另做管理界面。
- 目标核对通过、任务确实办完时，留下一条技能提纲：目标、涉及的应用、前提、步骤说明。不把点击坐标当成可以回放的宏，也不另做播放器。以后遇到相似的事，把提纲放进上下文，由模型按当前屏幕改。
- 模型说 `done` 不会马上结束。本机再问一轮，对照用户原话和当前屏幕；核对明确达成才算办完，没达成就写进步骤继续办。模型不能自己跳过这轮。`stop` 和用户取消仍立刻停下，不再核对。发送、删除、提交、下单仍要口令；付款永远不执行，停在付款页是 NEEDS_HUMAN。

## 0.1.19：长程任务（学 Mika 的循环）

- 模型循环步数默认不再限制（与 Mika 设置默认一致）。有限上限时，用尽后再给一轮只能 `done`/`stop` 的收尾。
- 模型可以一次给出 1–20 个已知动作（`do_steps`），本机逐个执行、按 `expect` / `expect_gone` / `expect_app` / `wait_stable` 等界面，不再为加载再问模型。整批只算一步。
- 屏幕会变之后不用旧编号；发送、删除、提交、下单不放进一批，改走原来的确认闸；付款永远不能进一批，也不会执行。
- 任务在跑时屏幕不熄灭（不改系统超时和亮度）。操作守卫的「同一界面」门槛按长任务放宽到 5 次提醒、10 次叫停。

## 0.1.18：主备模型

- 设置里 OpenAI 和 DeepSeek 可以同时填写，并选定主模型。平时用主模型。
- 主模型额度不够、Key 被拒、找不到模型、限流重试仍失败、或网络不通时，若备用已配好，自动改用备用，不再停下来让人改设置。
- 备用还没填时，会说明主模型为什么失败，并提示在设置里填写备用 Key。已保存的 OpenAI 配置在升级后仍是主模型，不必重填。
- 探测仍不带 Key。DeepSeek 不发送截图。Key 仍只存本机，不写日志、不进源码。

## 0.1.17：429 说清楚是额度还是限流，探测不再占用额度

- 探测 GET `/models` 不再带 API Key，避免每次开口都先耗一次额度；能连上（含 401/403/429）仍视为网络通。
- 对话接口若返回 429，会区分额度不够和暂时限流（只取 error.type / code / message，不把响应体写入日志）。限流会自动重试两次。
- 找不到模型名时，会提示在设置里更换。

## 0.1.16：模型连不上时如实说明，不再乱读屏

备用机若打不开当前模型接口（例如官方 OpenAI），先探测再停下，用中文说明当前接口从这台手机打不开，请改 Base URL 或改用 DeepSeek。不再在失败后把问话当成读屏、把首页字回给用户。本地意图闸去掉过于宽的「是什么」。含 0.1.15 的 onDestroy 实例守卫。

## 0.1.15：HarmonyOS 上只读无障碍不回调 onServiceConnected

升级或重启后，系统可能已经绑定 `ReadonlyAccessibilityService`，却不调用 `onServiceConnected`，首页会一直显示「只读无障碍正在连接」。改为在 `onCreate` 记下实例，并补上 `super.onServiceConnected()`。

## 0.1.14：按简一真机意见收紧间距

- 结构和颜色按 0.1.13 方案不变；连接条不加浅底、不加粗。
- 底边安全区：root 只垫左/上/右系统 inset，底垫为 0。底部操作区自己垫 `max(navigationInset + 12dp, 20dp)`，避免导航 inset 为 0 时「去办」贴到屏幕物理底边（手势区）。
- 中间这一组：8 dp 状态圆点右边距改为 8 dp（原先 12）；标题、副标题在这一组里把行高收到字号本身（28sp / 16sp），去掉多余 leading，可见间距分别为标题到副标题 10 dp、副标题到「启动服务」28 dp。
- 「设置」热区至少 44×44 dp（minWidth / minHeight / 布局高度都是 44，文字右对齐页边 20 dp）。
- 自检：`HomeCheck` 增加对上述底垫公式、圆点间距、10/28 可见间距和设置热区的源码断言。

## 0.1.13：首页按简一方案重做（状态 + 对话）
- 方案在 `/workspace/yuanhou-ui/SPEC.md`。首页从上到下：状态栏（WindowInsets，浅底深图标，#F5F5F2）→ 标题栏「远手」/「设置」→ 连接条「微信 / 手 / 网口」（正常时灰字绿点；未连为琥珀色字和点并写「未连」；微信需重新绑定写红色「需重绑」）→ 对话区 → 底部操作区（在导航 inset 之上再留 12dp）。首页不写 ESP32、不写 IP，点连接条进设置对应的那一项。
- 四种状态：**已就绪**（大字 + 一句副标题；微信没连上时副标题改成「本机可以下指令，微信还没连上」，大字不变）；**未就绪**（只写最要紧的一个原因 + 一个处理它的按钮，例如「启动服务」「去系统设置」，这时不放输入行）；**正在办**（踢头「正在办」、任务原话、带时间的步骤日志，底部「停止这件事」文字按钮 + 「更正或补充」输入行）；**等你确认**（问句、要点卡片、当前屏幕缩略图、「取消」/「确认」两个按钮，「确认」交的就是确认闸要的口令「发送」/「确认下单」）。
- 整机就绪仍是：服务在跑 + 手已连（屏幕已设好）+ 网口在听 + 只读无障碍在运行；微信没连不算未就绪。
- 确认页的文字全部来自确认闸当时的申报（对象、内容、金额），不写示例菜名和价格；付款不出确认页，仍由本人在手机上操作。微信里确认或取消后，首页立即离开「等你确认」。缩略图只在本次运行已授权截图时有（设置 › 调试 › 申请截图授权），只在内存里。
- 口令、启动/停止服务、重连、急停、网口地址、手的详细状态、调试（控制自测、桌面自测、截图授权、详细状态、日志）都搬进「设置」。口令默认圆点，可显示/复制/重新生成，离开页面自动收起，不写日志。
- 微信交来的任务等确认改为每次 90 秒（仍是两次）；首页和局域网发起的任务仍是 12 秒 × 2。确认闸规则不变。
- 自检：`jvmcheck/run.sh` 新增 `HomeCheck`（确认页文案与口令、付款不出确认页、等确认时长按来源、首页不含口令/IP/ESP32/「执行」/微信绿、字号只用五档、自测在设置里）。

## 0.1.12：微信官方 Bot 通道（从随身微信交代事情）
- 走腾讯官方 iLink Bot 接口（`https://ilinkai.weixin.qq.com/ilink/bot/`：`get_bot_qrcode?bot_type=3` 扫码、`get_qrcode_status` 等确认、`getupdates` 长轮询、`sendmessage` 回发），协议照 Mika `WechatApi.kt` 移植（`wechat/WechatApi.java`，纯 Java + HttpURLConnection）。**不是**个人微信的非官方协议，也不去点微信 App 的界面。消息会经过微信后台；远手不另搭服务器。
- **只收绑定本人的私聊文字**（`from_user_id == ilink_user_id`）；群消息、图片/语音/文件、别人发的一律忽略。同一条消息只办一次。
- 进来的话走和首页输入框 / 局域网 `/say` / `DEBUG_SAY` 同一条路：`LocalTextChannel.deliver → TaskSession`，没有第二个任务引擎。有任务在办时，非「取消」的话不交（回一句「还在办」，同 `/say` 409）；「取消/不对/停」随时立即交。空闲时只发一个「发送」「确认下单」「确认付款」不当新任务。
- 微信交来的那件事办的过程中，远手说的话（复述、进度、结果、需要本人处理的提示、结局）都发回这条私聊。**确认可以在微信里回**（这点比 Mika 多）：发微信回「发送」、下单回「确认下单」，「取消」「不对」就停，「好的」之类含糊回答按不同意处理；确认闸规则本身没变。别处（首页/局域网）发起的任务在等确认时，微信里不替它确认，只能取消。
- **付款永远不代付**：付款没有口令，微信里回什么都不会付款，会提示你本人在手机上操作。登录、验证码、密码页照旧由 SafetyGuard 叫停，并在微信里说明需要你在家里那台手机上亲手处理。
- 发回微信前会把 Bot 令牌、局域网口令、模型 Key、SIP 密码和像令牌的长串打码；日志只写状态和 HTTP 码，不写令牌、游标、context_token、用户 id、二维码内容。
- 绑定信息（bot_token、baseurl、ilink_bot_id、ilink_user_id、游标、context_token）用 Android 密钥库 AES-GCM 加密，只存本机私有 prefs `wechat_prefs`；不进源码、gradle、README、界面。
- 随前台服务起停（「启动服务/停止服务」一个开关管局域网 + 手 + 微信）：只有已绑定且开着「接收微信任务」才长轮询。401/403 视为登录失效：标记「需重新绑定」、停轮询、不崩溃。没有新增权限。
- 界面：首页不改（等简一方案）。临时入口在「模型与通话配置」页顶部：状态、扫码绑定（本机用 ZXing 画二维码）、暂停/开始接收、解除绑定。新首页可直接调 `wechat/WechatControl`（`state / bound / receiving / startLogin / setReceiving / unbind`）。
- 自检：`jvmcheck/run.sh` 新增 `WechatCheck`（路径/请求头/请求体、只收本人私聊、路由、确认走微信但付款不执行、401/403、打码、日志不带机密）。

## 0.1.10：首页改成安静的状态页
- 首页只剩：抬头「远手 · 家里备用机的手」（右上角齿轮进「模型与通话配置」）；状态卡（大字「已就绪 / 未就绪」+ 一句原因，「手 · ESP32」已连接/未连接，「家里网口」监听中 IP:3334 / 未开启）；主按钮「启动服务 / 停止服务」，次要「重连」「急停」；「口令」行；「说一句要办的事」+「执行」+ 取消/读屏/不对/发送/确认下单。
- 口令默认隐藏（显示为圆点），点「显示」才露出，离开页面自动收起；「复制」放进剪贴板。「重新生成口令」收在口令行「···」里，还要再确认一次。口令仍不写日志。
- 自测按钮、自测输入框、桌面自测、截图授权、详细两件套状态、局域网详情（不含口令）、模型/SIP 说明、本地意图记录、日志都收进底部默认折叠的「调试」。`--ez selftest true` 和「开始桌面自测」会先自动展开调试区、把自测框滚到屏幕上再用 HID 鼠标去点。
- 「已就绪」= 两件套就绪（Wi-Fi 透传或 GATT + 已设屏幕）且只读无障碍已开。行为（局域网通道、确认闸、安全规则、调试广播、签名、权限）都没改。重启后要手动开时，点「启动服务」。

## 0.1.9：局域网指令通道（手机和 ESP32 都可以不插电脑）
目标：ESP32（Wi-Fi 透传 `s3-phone-hand.local:3333`）和备用机都独立供电、都不插电脑也能跑。USB 只在**第一次装远手、以后升级版本**时用一次；装好 0.1.9 之后，家里 Mac 上的 Goji 走家里 Wi-Fi 和远手说话，不再需要 adb。

- **只在家里局域网使用，不要做端口转发，不要暴露到公网。** 服务只绑手机自己的 Wi-Fi IPv4，只接受私有网段（10/8、172.16/12、192.168/16、169.254/16）来的连接。
- 端口 **3334**（3333 是 ESP32）。被占用时不换端口，主界面和日志会写明，并按原端口稍后再试。
- 随前台服务起停：点「启动前台服务」后开启，停止服务就关闭。主界面「局域网指令通道」显示状态、`http://<手机 IPv4>:3334`、mDNS 名和口令；上次成功的监听地址记在 prefs `lan_last_addr`。
- mDNS / NSD：服务名 `yuanhou-hand`，类型 `_yuanhou._tcp`（同名冲突时系统可能改名，以主界面为准）。注意这只是服务广播，**不是** `yuanhou-hand.local` 主机名；这台 HarmonyOS 2.2 / Android 10 自己常常解析不了 `.local`。广播不稳时直接用主界面上的 IPv4。
- **口令**：第一次启动时用 SecureRandom 生成 24 字节（48 位十六进制），只存在本应用私有 prefs，不写日志、不进源码和 APK；主界面可「重新生成口令」（旧口令立即作废）。每个请求（包括 `/status`）都要带 `X-Yuanhou-Token: <口令>` 或 `Authorization: Bearer <口令>`，没带/不对一律 401、不返回任何内容。
- 接口（UTF-8，一个请求一个连接）：
  - `GET /status` → JSON：版本、监听地址/端口、Wi-Fi IPv4、只读无障碍是否在运行/是否在系统设置里开启、前台包名、截图是否已授权、两件套与 Wi-Fi 透传状态、会话阶段、是否在等确认、上次结局。
  - `POST /say`，正文纯文本或 `{"text":"读屏","timeout":60}` → 走和 adb `DEBUG_SAY` 完全相同的 `LocalTextChannel.deliver` → `TaskSession`。等这一件事办完（或它新问了一次确认口令，返回 `AWAITING_CONFIRM`，再 `/say` 口令即可；或超时 `TIMEOUT_STILL_RUNNING`，任务继续，可 `/say 取消`），返回 `ok`、`outcome`（`TaskSession.End` 名，或 `FINISHED` / `NO_TASK`）、远手说的话 `spoken`、这次「读屏」的界面树 `tree`、本地意图记录 `report`。有任务在办时再交一句非「取消」的话返回 409；「取消」任何时候都立即交进去。确认闸、付款永远交本人都不变。
  - `GET /tree` → 和 `DEBUG_TREE` 一样的界面树文字（同样写 tree-last.txt），text/plain。
  - `GET /screenshot` → 本次运行本人已在手机上点过「申请本次运行的截图授权」时返回 PNG；没授权返回 503 JSON，**局域网请求不会弹授权框**。没截图时用 `/tree`。
  - 其他路径 404。没有文件读取、没有 shell、没有 systemCommand。
- 开机：0.1.9 加了 `BOOT_COMPLETED` 接收器，**试着**启动前台服务（只在以前手动启动过时）。HarmonyOS 的自启动管理可能不放行，这里不做绕过；重启后的正规办法是打开远手、点一次「启动前台服务」。
- adb `DebugReceiver`（DEBUG_SAY / DEBUG_TREE）原样保留，作为插 USB 时的备用。
- Mac 客户端 `lan/yuanhou_lan.py`（Python 3 标准库）：
  ```
  export YUANHOU_LAN_TOKEN='<远手主界面上的口令>'
  export YUANHOU_LAN_HOST='<手机 IPv4>'     # 不设时先用 dns-sd 找 yuanhou-hand._yuanhou._tcp，再试 yuanhou-hand.local
  python3 lan/yuanhou_lan.py status
  python3 lan/yuanhou_lan.py say 读屏
  python3 lan/yuanhou_lan.py tree
  curl -s -H "X-Yuanhou-Token: $YUANHOU_LAN_TOKEN" http://$YUANHOU_LAN_HOST:3334/status
  ```
  退出码：0 成功，1 没办成/HTTP 错，2 连不上或缺参数，3 口令不对。

## 0.1.6（0.1.5 真机测试后的跟进）
- 演练下单会从语句里拆出带币种的金额（「18 元」「18元」「¥18」「￥29.90」），复述不再把整句当成对象而把金额写成「无」；没有 ¥ / ￥ / $ / 元 的光秃数字不当金额，句子里本来就没有金额时复述仍可以是「金额：无」。
- Wi-Fi 透传主机不再写死 `192.168.1.113`：DHCP 会变，而且这个地址在真机上已经是手机自己的 wlan0，连会失败。默认改用 `s3-phone-hand.local`；设置页可填写主机名或 IPv4（prefs：`wifi_host` / `wifi_port`，留空用默认名）。连上后若对端是 IPv4，会记下 `wifi_last_good_host`。
- 这台荣耀 Android 10 / HarmonyOS 2.2 上，普通 `InetAddress` 很可能解析不了 `.local`（本轮没有做完整的组播 mDNS / NsdManager，也不宣称 mDNS 已经能用）。解析失败时改用上次成功连上的 IP，并在日志里写明原因。请在设置里填当前 IP，或依赖上次成功地址。
- 打包必须用同一把 Mac `debug.keystore`，见上方「编译 / 安装」。

## 0.1.5：主屏 + ESP32 屏幕端口、加严确认闸、意图闸
按 Mika 的 `ScreenPort` 接缝思路（只学思路，Java 重写，没有搬 `AndroidScreen.kt`）：
- **port/**：`ScreenPort`（接口）→ `MainScreenEsp32Port`（实现）外面套 `AppAccessScreenPort`（装饰器，策略在 `AppAccessPolicy`）。
  - 读屏：只读无障碍 `com.yuanhou.hand/.screen.ReadonlyAccessibilityService`（capabilities=1，不声明手势，不调用 performAction），主屏 display 0。
  - 点击/长按/滑动/滚轮/返回/主页/回车：ESP32-S3 HID（Wi-Fi 透传 `s3-phone-hand.local:3333`，GATT 备用）。失败就报失败，不退回无障碍、不换通道重发、不加抖动或弧线。
  - 打字：点框拿焦点 → Ctrl+End + Backspace×(字数+12) 清空（不用 Ctrl+A，不用右方向键：这台机的百度输入法会吞掉）→ 读回必须已空 → 剪贴板 + Ctrl+V → 读回必须与要打的字**完全一致**；任一步不符返回 `VERIFY_FAILED`，不再贴第二次。密码框直接 `NEEDS_HUMAN`，不点、不贴、不做粘贴探测。
  - 编号 ref 绑定 snapshotId；动过一次之后旧编号 `REF_EXPIRED`。
  - 没有、也不打算有：虚拟屏 / AgentDisplay、ShellService / BuiltinShellManager、无线调试配对、MainScreenSpill、systemCommand、openAppCopy、任务挪动、adb tcpip 保活。
- **gate/**：`IrrevAction`（SEND / SUBMIT / DELETE / OTHER / ORDER / PAY）、`IrreversibleDetector`（按钮名、资源 ID、非搜索框回车、付款页、屏上应付金额时的「确定」）、`ConfirmationGate`（加严版，见下）。
- **agent/IntentGate**：讨论 / 读取 / 操作。只有「操作」才进入 读屏 → 动作 → 执行 → 等稳定 → 再读屏 的循环；「讨论」只回话，「读取」只读一屏回答（敏感页不发给模型）。
- **model/**：OpenAI / DeepSeek 都走 chat/completions。Key 只从应用设置（本机私有目录）读，设置里没填时才看本机运行时环境变量 `OPENAI_API_KEY` / `DEEPSEEK_API_KEY`。没有 Key 时不调用，并提示「请在设置里填写 … 的 API Key」。仓库里没有任何 Key、也没有 `.env`。

### 确认闸（比 Mika 默认更严）
- 发送、删除、提交、下单（以及屏上有应付金额时点「确定/确认」）：先复述**对象、内容、金额**，等本人说出口令——「发送」「确认删除」「确认提交」「确认下单」「确认执行」（可带「吧」）；「好的」「可以」「嗯」都算含糊 = 不做。12 秒没回答只再问一次。
- 该填的没申报（发送没对象/没正文、下单没金额、屏上有应付金额却没报金额）→ 不问也不做。
- 同意一次只放行紧接着的那一个动作：执行前重新读屏核对同一类、同一 App、对象在屏上（发送只认标题栏）、输入框里的字 = 复述的正文、金额在屏上（优惠/运费/券旁的数不算）、按钮还在原处；中间做了任何别的写操作（包括打字）就作废；`MAX_STEPS = 3`。本任务里拒绝过的同类操作不再问。
- **付款永远交给本人**：不问、不代付、遇到付款页直接停。

## 模块
- hand：HandProtocol / HandGattClient（复制自测试应用）/ HandExecutor（阻塞式动作、剪贴板+Ctrl+V）/ SuiteState（两件套就绪判定）
- service：OrchestratorService（前台服务、常驻通知、心跳看门狗、2–5 s 重连、SET_SCREEN）
- screen：ReadonlyAccessibilityService（只读，默认未开启）/ ScreenSnapshot / ScreenCapture（MediaProjection，需本人同意）
- policy：OperationGuard（无效果、兜圈、空白即停）/ ConfirmationPolicy（按钮词表，供 gate/IrreversibleDetector 复用）/ SafetyGuard（密码、验证码、支付密码、解锁页、环境异常即停）/ InterruptionGuard（广告弹窗、直播跳转、离开应用；关闭走 HID，不问模型）
- model：ModelConfig（空位）/ ModelKeys（Key 解析：设置 → 运行时环境）/ ModelClient（OpenAI / DeepSeek chat/completions，仅在有 Key 且同意后调用）/ ModelAction / PromptText
- memory（0.1.20）：StepHistory（长历史收成结构化摘要）/ GoalCheck、DoneGate（done 前强制核对）/ AgentMemory（偏好和应用经验）/ SkillBook（办成路线的提纲）/ Playbook（本机 playbook/ 目录）
- session：CallChannel / LocalTextChannel（桌上文字自测，不出声）/ SipCallChannel（内嵌 Liblinphone 5.5.39 的 SIP 用户代理：来电先查两件套、自动接听、下行写文件听写、合成语音在同一通话上行播放）/ CallVoice（只 synthesizeToFile，不外放）/ CallTranscriber（Vosk 离线中文听写）/ SipConfig（本人自己填）/ TaskSession
- ui：MainActivity / SettingsActivity
- lan（0.1.9）：LanProtocol（纯 Java：请求解析、口令、网段、JSON）/ LanServer（前台服务里的 3334 HTTP 服务 + NSD）；service/BootReceiver（开机试着启动）

## 语音（0.1.2 起）
- 主通路：炳霖打进远手自己的 SIP 账号（设置页本人填写），在通话里听和说。手机不外放、不用房间麦克风（RECORD_AUDIO/CAMERA 已从合并清单移除）。
- Linphone 仓库：https://download.linphone.org/maven_repository ，`org.linphone:linphone-sdk-android:5.5.39`（AAR minSdk 23）。
- 听写模型（不进 APK）：把 vosk-model-small-cn-0.22 解压为 /sdcard/Android/data/com.yuanhou.hand/files/vosk-model-cn/

## 对照 Mika
见 docs/mika-lessons.md。自检（桌面 JVM，不连手机、不调模型）：`sh jvmcheck/run.sh`（PolicyCheck / IntentCheck / GateCheck / PortCheck / LanCheck / WechatCheck / HomeCheck / ModelActionCheck / LongHorizonCheck / InterruptionCheck）

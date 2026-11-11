# 远手对照 Mika 的取舍（开发备忘，不是用户说明书）

对照源码：https://github.com/wangzan101/Mika（Apache-2.0）
- 本地克隆：/workspace/Mika，HEAD `17e9d9d2`（2026-11-10 20:34 +0800，app versionName 0.1.39）
- 备用机上装的是 0.1.38 → tag `v0.1.38` = `5d3c7f9c`（2026-11-07）。下面引用的文件在 v0.1.38 里都已存在；
  v0.1.38→HEAD 对这些文件只有小改动（IrreversibleDetector +2/-1、ScreenDiagnosis +5/-1、StopRules +9/-3、TaskState +2），不影响下面的结论。
- 路径缩写：`core/` = `core/src/main/kotlin/com/wangzan101/mika/core/`，`app/` = `app/src/main/java/com/wangzan101/mika/`

远手只借鉴思路和规则，没有拷 Mika 的代码：Mika 是 Kotlin + 协程，远手是 Java；下面列的都是在远手里重写的。

## 1. 学了什么（源码里确实有的）

| Mika 里的做法 | 源码位置 | 远手怎么落 |
|---|---|---|
| 一句话办事：用户一句话 → 循环「读屏 → 问模型要一步 → 执行 → 再读屏」，直到回答、失败或取消 | `core/agent/AgentLoop.kt`、`core/agent/TaskState.kt` | `session/TaskSession.modelLoop`：每步读主屏界面树 → 安全闸 → 守卫 → 模型 → 门闩 → HID 执行；0.1.19 起步数默认不限制，并支持一次多步 `do_steps`（学 `BatchRunner` / `UntilWait`） |
| 任务状态明确：Running(step)、WaitingConfirmation、WaitingTakeover、Succeeded / Failed(kind) / Cancelled；结局种类由产生它的代码给，不从文字里猜 | `core/agent/TaskState.kt`、`core/agent/StopRules.kt` | `TaskSession.Phase`（IDLE/READBACK/OBSERVING/DECIDING/ACTING/WAITING_CONFIRM/ENDED）+ `TaskSession.End`（DONE/CANCELLED/DECLINED/NEEDS_HUMAN/GUARD/HAND_FAILED/MODEL_STOP/STEP_LIMIT/NOT_READY/BLIND） |
| 停下后交代停在哪、做了什么（`StopSummary`，没模型时按记录直接拼一段） | `core/agent/StopSummary.kt` | `TaskSession.end()`：不是办完时说「停在第 N 步，最后做的是 …，没有发出、没有下单、没有付款」，只按本地记录拼，不再问模型 |
| 关键操作一次性同意：申报（对象/内容/金额/App）→ 用户只能同意这一次 → 执行前用新读的一屏核对：前台 App 相同、对象在屏上（发送时只认顶部约 15% 标题栏）、要发的字等于输入框里的字、应付金额在屏上（优惠/运费/券旁的数不算）；同意后超过 3 步或中途做了打字以外的写操作就作废；同一任务里拒绝过的同类操作不再问；付款永远不放行 | `core/gate/ConfirmationGate.kt` | 0.1.4：`policy/OneShotGrant`；0.1.5 起由 `gate/ConfirmationGate` 取代（见第 5 节，打字也作废同意） |
| 「先起草、确认后再发」：确认绑定的就是输入框里那段话 | `ConfirmationGate.contentMatches` | 模型先 `paste` 起草，下一步点发送才进门闩；念给用户的正文以输入框里读到的字为准，核对不一致就不发 |
| 按钮词表兜底（只认按钮名，拿不准宁可多拦）：强词 + 「待/已」前缀、「成功/记录/方式…」后缀排除；发送类允许多几个字；评论/回复只认等于或后跟标点；支付宝/支付方式先去掉；资源 ID 拆词；非搜索框里的回车按发送拦 | `core/gate/IrreversibleDetector.kt` | `ConfirmationPolicy.classifyLabel` / `inferGate`（子集，归到远手三道门闩 + DELETE）；`TaskSession` 对 `enter` 动作：焦点框不是搜索框就按发送门闩 |
| 需要本人处理的页面分类：captcha、payment（支付密码/确认支付…）、密码框、短信验证码；没有可读元素为 BLANK、界面还在变为 LOADING | `core/uistate/ScreenDiagnosis.kt` | `policy/SafetyGuard`：新增 CAPTCHA、PAYMENT_PAGE 两类及短信验证码词；`isBlank()`；命中即停，不把这一屏发给模型 |
| 操作守卫：动作没效果（界面指纹不变）、反复回到同一界面、连续读不出东西时先提醒模型、再叫停；**只凭动作签名重复不算停滞**（相同滚动可以逐页读到新内容） | `core/guard/OperationGuards.kt`、`core/guard/GuardRules.kt`（v0.1.38 的 `beforeAction` 已直接返回空） | 新增 `policy/OperationGuard`：无效果 2 次提醒/4 次停；同签名只在上一步也没效果时才算（3 提醒/5 停）；同一界面 3 提醒/6 停；连续空白 3 次停。提醒写进给模型的历史（「提醒：…」） |
| 读屏：跳过太小节点（MIN_SIZE 5）、密码框不带文字、带提示语/选中/可用状态；元素带编号 ref，模型按 ref 选目标；指纹覆盖文字/位置/焦点/勾选 | `app/cap/phone/a11y/ScreenReader.kt`、`core/uistate/UiTree.kt` | `screen/ScreenSnapshot`：`ref`、`byRef`、`hint/selected/enabled`、`fingerprint()`、`focusedField()`、`isSearchField()`、`titleBandBottom()`；`ReadonlyAccessibilityService` 只多读这些属性，仍不调用任何 performAction |
| 动作后等界面稳定再判（SETTLE / RECHECK：看起来没变就再看一次） | `core/uistate/EffectTuning.kt`、`core/uistate/UntilWait.kt` | `TaskSession.settledSnapshot`：读一屏、隔「两次主屏操作之间」再读，指纹相同才算静；不同就按「打开或切换页面后」再等，最多两次；空白时按「较重页面」等一次。只用设计第 7 节已有的间隔 |
| 打开 App 后先看一眼那一屏：读不出内容就在结果里告诉模型（`unreadable_window`）；确认申报时要求申报的 App 就是前台 | `core/cap/phone/AppTools.kt`（`unreadable()`）、`ConfirmationGate.declare` | 模型的 `open_app`：显式 Intent 打开后读一屏，前台不是目标包就停（这条「前台必须一致」是远手自己收紧的，Mika 打开后不做这项比较） |
| 没人在场的任务遇到要确认的一步不等人，直接不做并如实说明 | `docs/modes.md`、`core/agent/UserExchange.confirm`（Unattended 分支） | `passGate`：通话通道不在线就不问、不执行，结局 DECLINED |
| 意图先分「讨论 / 读 / 操作」 | `core/agent/IntentGate.kt`（再请一次模型判断） | **只学了很小一部分**：本地按关键词判断这句话是否可能涉及发出/下单/付款，复述时提前说明会先念给他。没有加第二次模型调用 |

## 2. 明确不学

| Mika 的做法 | 源码位置 | 为什么不学 |
|---|---|---|
| 后台虚拟屏（VirtualDisplay，在隐藏屏上开 App、操作、截画面） | `app/cap/phone/display/*`、`app/cap/phone/shell/ShellService.kt`（`setLaunchDisplayId` / `am start --display`）、README「hidden background screen」 | 设计第 1/3 节：远手只在主屏操作；这台机 Android 10 也走不通 |
| 用无障碍执行点击、手势、滚轮（`dispatchGesture` / `performAction`） | `app/cap/phone/a11y/AndroidScreen.kt`、`ScreenAccessibility.kt`、`WheelDriver.kt` | 远手无障碍只读；点击/滑动/输入只走 ESP32 BLE HID，HID 失败不退回无障碍 |
| 无线调试配对 + shell 服务（Shizuku 式）打开 App、跨用户启动 | `app/cap/phone/shell/ShellService.kt`、`docs/setup-guide.md` | 运行时不用 ADB，也不把 adb tcpip 做成跨重启保活 |
| 拟人节奏：落点在元素中心附近随机偏移、滑动走二次贝塞尔弧线、时长上下浮动、按下前随机停顿；网页鼠标同样 | `core/cap/phone/GesturePacing.kt`、`core/uistate/Pace.kt`、`core/cap/web/cdp/InputPacing.kt`、`core/cap/phone/TypingPacing.kt` | 设计 6.2/7：不加抖动、落点偏移、曲线轨迹；落点取中心，直线最短路径 |
| 本人接管后再交回继续（WaitingTakeover，无超时等待） | `core/agent/UserExchange.takeover` | 人在电话另一头，没法在手机上接管；第一期遇到登录/验证码/付款密码页一律停下说明 |
| 询问模式只在花钱时问，发消息、发帖、删除、改设置都直接做；自主模式连付款也不问 | `docs/modes.md`、`ConfirmationGate.amountBinds` | 设计 6.4 更严：对外发送也要口令「发送」；付款、删除远手从不执行；没有「自主模式」 |
| 付款面板/金额用看图复核（自绘界面时按截图里认出的字核对） | `ConfirmationGate.permits(seen=…)`、`core/vision/*` | 远手读不到界面树时门闩不放行，不靠截图核对确认内容 |
| 定时任务、批量执行、记住路线回放、技能学习、MCP、内置浏览器、沙箱 | `core/agent/BatchRunner.kt`、`core/replay/*`、`core/teach/*`、`core/cap/mcp/*`、`core/cap/web/*` | 不在第一期范围；批量/挂机/群发是明确不做的事 |

## 3. 映射到远手模块

- **TaskSession**：状态机（Phase/End）、复述（「不对」→ 停下请重说）、`settledSnapshot`、ref 选目标、`passGate`、守卫提醒写进历史、停下后交代进度、`open_app` 前台核对、`enter` 门闩。
- **ConfirmationPolicy**：口令不变（发送 / 确认下单 / 确认付款 / 取消 / 不对；12 s；只再问一次；含糊当取消）；按钮词表换成 Mika 规则的子集；新增 `Gate.DELETE`（无口令，遇到即停）；`neverExecuted()` 覆盖付款和删除。
- **OneShotGrant**（0.1.4，已删除）：由 0.1.5 的 gate/ConfirmationGate 取代。
- **OperationGuard**（新）：Mika OperationGuards 的远手版（门槛按 20 步短任务收紧）。
- **SafetyGuard**：增加 CAPTCHA / PAYMENT_PAGE / 短信验证码 / BLANK。
- **ReadonlyAccessibilityService / ScreenSnapshot**：多读 hint/selected/enabled、跳过小节点、ref、指纹。仍是只读，仍未在系统里开启（需要炳霖本人明确同意）。
- **HandExecutor**：只加了 `enter()`（HID 回车 usage 0x28）；是否算「发出」由 TaskSession 门闩判断。没有加任何无障碍或 shell 回退。
- **ModelClient / ModelAction**：提示词改为「优先 ref、先起草再发、门闩申报 to/text/amount、提醒出现就换办法或停」；`ModelAction.ref`、`enter`。

## 4. 验证情况

- `jvmcheck/run.sh`：桌面 JVM 上跑策略自检（词表、口令、一次性同意、金额核对、安全闸、ref、守卫），不连手机。
- 以上模型循环、门闩、守卫都**没有在真机上跑过**：远手无障碍未开、模型 Key 未填，模型路径走不到。

## 5. 0.1.5 补充：按 ScreenPort 接缝重做主屏 + ESP32

| Mika | 远手 0.1.5 |
|---|---|
| `core/uistate/ScreenPort.kt`（接口） | `port/ScreenPort`（Java，阻塞式；无 displayId；systemCommand / openAppCopy / awaitAppFree 故意不在接口上） |
| `app/.../a11y/AndroidScreen.kt`（虚拟屏 + 无障碍手势 + shell 注键） | `port/MainScreenEsp32Port`：只读无障碍读主屏树，ESP32 HID 动手；没有搬 AndroidScreen 的任何代码 |
| `core/uistate/AppAccessScreenPort.kt` | `port/AppAccessScreenPort` + `AppAccessPolicy`（装饰器，不写进 ESP32 端口） |
| `core/gate/IrreversibleDetector.kt` | `gate/IrreversibleDetector`（复用 ConfirmationPolicy 词表，细分 SUBMIT，补「有应付金额时的确定」= OTHER） |
| `core/gate/ConfirmationGate.kt`（询问模式只问花钱） | `gate/ConfirmationGate`：发送/删除/提交/下单/OTHER 全部复述内容和金额并等口令；任何别的写操作（含打字）都作废同意；PAY 永远 UNSUPPORTED |
| `core/agent/IntentGate.kt` | `agent/IntentGate`：模型判 DISCUSS/READ/ACT，失败时本地关键词兜底；只有 ACT 进入循环 |
| `ContextPastePolicy`（密码框不探测粘贴） | 密码框直接 NEEDS_HUMAN，不点、不贴 |

0.1.4 的「清空没清干净就按右方向键再清一轮」已删除（百度输入法吞右方向键）；现在没清干净直接 VERIFY_FAILED。
旧的 `policy/OneShotGrant` 已由 `gate/ConfirmationGate` 取代并删除。

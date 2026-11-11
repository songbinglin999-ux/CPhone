#!/bin/sh
# 桌面 JVM 上跑自检：只编译纯 Java 的 policy/、gate/、agent/、port/（不含 Android 适配器）、ScreenSnapshot、LocalIntent、ModelKeys、Timing、lan/LanProtocol、wechat/（MiniJson、WechatApi、WechatPolicy）、memory/（摘要、记事、技能、目标核对）、model/PromptText
# （android.graphics.Rect 用替身），不进 APK、不连手机、不调用任何模型服务。
set -e
cd "$(dirname "$0")"
M=../app/src/main/java/com/yuanhou/hand
rm -rf out && mkdir out
javac -encoding UTF-8 -cp lib/json-20240303.jar -d out stub/android/graphics/Rect.java $M/screen/ScreenSnapshot.java $M/util/Timing.java \
  $M/policy/ConfirmationPolicy.java $M/policy/OperationGuard.java $M/policy/SafetyGuard.java \
  $M/gate/IrrevAction.java $M/gate/IrreversibleDetector.java $M/gate/ConfirmationGate.java $M/gate/ConfirmCopy.java \
  $M/agent/IntentGate.java $M/model/ModelKeys.java $M/model/ModelAction.java $M/model/BatchPlan.java $M/policy/UntilCheck.java \
  $M/port/ActionResult.java $M/port/PortException.java $M/port/HandPort.java $M/port/TreeSource.java \
  $M/port/AppLauncher.java $M/port/Sleeper.java $M/port/ScreenPort.java $M/port/MainScreenEsp32Port.java \
  $M/port/AppAccessPolicy.java $M/port/AppAccessScreenPort.java \
  $M/session/LocalIntent.java $M/lan/LanProtocol.java \
  $M/memory/Sensitive.java $M/memory/StepHistory.java $M/memory/RouteText.java \
  $M/memory/GoalCheck.java $M/memory/DoneGate.java $M/memory/AgentMemory.java \
  $M/memory/SkillBook.java $M/memory/Playbook.java $M/model/PromptText.java \
  $M/wechat/MiniJson.java $M/wechat/WechatApi.java $M/wechat/WechatPolicy.java src/PolicyCheck.java src/IntentCheck.java src/GateCheck.java src/PortCheck.java src/LanCheck.java src/WechatCheck.java src/HomeCheck.java src/ModelActionCheck.java src/LongHorizonCheck.java
java -cp out:lib/json-20240303.jar PolicyCheck
java -cp out:lib/json-20240303.jar IntentCheck
java -cp out:lib/json-20240303.jar GateCheck
java -cp out:lib/json-20240303.jar PortCheck
java -cp out:lib/json-20240303.jar LanCheck
java -cp out:lib/json-20240303.jar WechatCheck
java -cp out:lib/json-20240303.jar HomeCheck
java -cp out:lib/json-20240303.jar ModelActionCheck
java -cp out:lib/json-20240303.jar LongHorizonCheck

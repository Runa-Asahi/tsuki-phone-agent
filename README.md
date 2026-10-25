# 荣耀手机 Agent（Honor Phone Agent）

面向 **荣耀 PPG-AN00 / MagicOS 10（Android 16）** 的本机手机 Agent：

- **脑**：本机 [MNN Chat](https://github.com/alibaba/MNN) OpenAI 兼容 API（默认 `http://127.0.0.1:8080/v1`），可选云端混合
- **手**：`AccessibilityService`（日常无需 ADB）
- **眼**：无障碍节点树为主，`takeScreenshot` 为辅
- **安全**：支付/发送/删除等确认闸 + 敏感包名人工接管 + 急停浮层/通知

包名：`com.runasakura.phoneagent`  
许可：Apache-2.0  
机型优化说明：仅荣耀 MagicOS，不覆盖华为 EMUI/麒麟。

## 功能一览

| 模块 | 状态 |
|---|---|
| 无障碍 UI dump（text/desc/bounds/clickable/id） | ✅ |
| launch / tap / long_press / swipe / type / clear / back / home / wait | ✅ |
| Agent 循环：目标 → LLM → 观察 → 行动 | ✅ |
| LocalMnnClient + 可选 CloudClient + hybrid 路由 | ✅ |
| 安全确认 / 敏感包名 / 急停 | ✅ |
| 主界面 + 设置 + 前台服务 | ✅ |
| 「打开设置」快路径 + ACTION_SETTINGS（MagicOS） | ✅ |
| 本机截图 → 云端 VLM 完整多模态 | ⚠️ 预留（need_screenshot / takeScreenshot API 已接，上传链路二期） |
| 端侧 GUI 小模型 | ❌ 二期 |

## 在 PPG-AN00 上安装使用

### 1. 安装 MNN Chat（脑）

1. 安装 [MNN Chat](https://play.google.com/store/apps/details?id=com.alibaba.mnnllm.android.release)
2. 下载 **Qwen3-0.6B / 4B（W4）**，后端优先 **Hexagon**，不行再 OpenCL/CPU
3. 打开 MNN Chat 的 **API 服务**（`127.0.0.1:8080`）
4. 按 [docs/HONOR_MAGICOS10.md](docs/HONOR_MAGICOS10.md) 做保活

### 2. 安装本 Agent（手）

**预编译：**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**或自行编译：**

```bash
git clone git@github.com:Runa-Asahi/honor-phone-agent.git
cd honor-phone-agent
git checkout feat/mvp-agent
# 需 JDK 17 + Android SDK 36
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Debug 包名为 `com.runasakura.phoneagent.debug`。

### 3. MagicOS 权限

1. **无障碍**：设置 → 辅助功能 → 无障碍 → 开启「荣耀手机 Agent」
2. **通知**（Android 13+）：允许通知
3. **悬浮窗**（可选急停球）：应用内点「悬浮窗权限」
4. **保活**：应用启动管理 / 电池优化 / 最近任务加锁 —— 见 [docs/HONOR_MAGICOS10.md](docs/HONOR_MAGICOS10.md)

### 4. 跑起来

1. 打开本 App → 右上角设置 → 确认 MNN URL `http://127.0.0.1:8080/v1`、API Key、模型名
2. 路由选 `hybrid`（默认）或 `local_only`
3. 输入目标，例如：`打开设置并进入电池`
4. 点「开始」；可用通知或悬浮「急停」

## 构建命令

```bash
export JAVA_HOME=...   # JDK 17
export ANDROID_HOME=...
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

**Android 16 / 16KB 页对齐**：MVP 无 JNI；若日后嵌入 MNN `.so`，请用 NDK r27+ 并按 16KB linker 对齐打包。

## 文档

- [docs/CHECKLIST.md](docs/CHECKLIST.md) — 完整工程清单
- [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) — T1–T14 验收
- [docs/HONOR_MAGICOS10.md](docs/HONOR_MAGICOS10.md) — 保活/无障碍一页纸
- [prompts/SYSTEM_PROMPT.md](prompts/SYSTEM_PROMPT.md) — 动作 JSON Schema

## 架构

```
用户自然语言
  → AgentLoop
  → 眼：AccessibilityService.dumpUiTree (+ takeScreenshot)
  → 脑：LlmRouter → LocalMnnClient ↔ CloudClient
  → 手：GestureDescription / GLOBAL_ACTION_* / ACTION_SET_TEXT
  → SafetyGate + EmergencyStopOverlay
```

## 致谢与参考

- [alibaba/MNN](https://github.com/alibaba/MNN) — 端侧推理与 OpenAI API
- a11y Agent 模式为社区常见做法的自研实现（未拷贝第三方源码）
- 本仓库 Kotlin 实现，Apache-2.0

## 分支策略

仅通过 feature 分支 + PR 合入 `main`，禁止直接推 `main`。

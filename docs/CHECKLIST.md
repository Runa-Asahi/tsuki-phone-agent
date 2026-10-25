# 荣耀 PPG-AN00 手机 Agent 工程清单（MagicOS 10 / 混合推理 / 一期通用操控）

> 目标机：**樱小路露娜大人**（PPG-AN00）  
> 系统：**MagicOS 10.0**（Android 16，版本号 `10.0.0.175(C00E175R104P7)`）  
> SoC：**骁龙 8 至尊领先版**（Snapdragon 8 Elite Leading / SM8750 系，Hexagon v79）  
> 内存：**12 GB + 12 GB 智慧运存**；存储 **512 GB**；分辨率 **2800×1264**；电池 **7200 mAh**  
> 策略：**本机 MNN 为主脑 + AccessibilityService 为手 + 难任务可联网**；**仅针对荣耀 MagicOS，不覆盖华为/EMUI/麒麟**  
> 一期范围：**多 App 通用点击 / 滑动 / 输入 / 返回**，不做垂直 App 脚本  
> 性质：**研究与工程落地方案**；本稿不推代码、不发版

---

## 0. 一句话架构

```
用户自然语言
    → Agent 壳（规划 / 循环）
    → 眼：无障碍节点树（主）+ takeScreenshot（辅）
    → 脑：本机 MNN（OpenAI 兼容 API）↔ 可选云端 GUI/LLM
    → 手：AccessibilityService（tap / swipe / type / back / home）
    → 安全闸：支付 / 验证码 / 删除等强制确认 + 急停浮层
```

---

## 1. 机型画像与能力边界（按这台 PPG-AN00）

| 项 | 本机事实 | 工程含义 |
|---|---|---|
| SoC | 骁龙 8 至尊领先版 | 可走 **CPU / OpenCL / Vulkan / Hexagon NPU**；优先试 Hexagon + OpenCL，勿按麒麟路径规划 |
| Hexagon | v79（与 MNN 3.6.1 官方基准同代） | MNN 3.6.1+ Hexagon 后端在 8 Elite 上 Qwen3-0.6B W4 prefill 约 **2667 tok/s**（官方：Hexagon ≫ OpenCL ≫ CPU 的 prefill） |
| RAM | 实物 12 GB（+智慧运存） | 日常可稳跑 **0.6B–4B** W4；长上下文 + 截图 VLM 并存时预留 ≥3–4 GB；**不要一期硬上 7B/9B** |
| 分辨率 | 2800×1264 | 坐标归一化（0–1000）→ 像素时务必按真实宽高换算；横竖屏切换要重测 |
| Android 16 | API 36 级 | 16KB 页对齐、MediaProjection / 截屏限制更严；自编译 APK 用 **NDK r27+** 与 16KB linker 对齐 |
| MagicOS 10 | 荣耀定制层 | 无障碍、自启动、电池、后台杀进程都比 AOSP 更挑；见第 2 节 |
| 电池 | 7200 mAh | 本机推理可持续，但仍需前台服务 + 多任务加锁，否则会被杀 |

**模型体量建议（一期）**

| 角色 | 推荐起步 | 备注 |
|---|---|---|
| 规划 / 文本 | Qwen3-0.6B 或 1.7B（MNN W4） | 低延迟、测通管线用 |
| 主力本机 | Qwen3-4B（MNN W4） | 12 GB 上能力与发热较均衡 |
| 视觉 GUI | 一期优先 **云端** AutoGLM / GUI-plus；本机小 VLM 放二期 | 本机截图+小模型易卡、易杀后台 |
| 不上 | ≥7B 日常常驻、端侧硬跑完整 GUI Agent 模型 | 留给二期压测 |

参考：
- [alibaba/MNN](https://github.com/alibaba/MNN)
- [MNN 3.6.1 Hexagon 发布说明](https://github.com/alibaba/MNN/releases/tag/3.6.1)
- [MNN Hexagon 后端 README](https://github.com/alibaba/MNN/blob/master/source/backend/hexagon/README.md)
- [MNN Chat（Play）](https://play.google.com/store/apps/details?id=com.alibaba.mnnllm.android.release)

---

## 2. MagicOS 10 / 荣耀专属：权限与保活清单

> 路径以官方文档 + 设置搜索为准；不同小版本文案可能略有差异，**优先用设置顶栏搜索关键词**。

### 2.1 开启第三方无障碍（Agent「手」）

1. **设置 → 辅助功能 → 无障碍**  
   官方入口说明：[无障碍使用手机/平板](https://www.honor.com/cn/support/content/zh-cn15869276/)；手册 PDF：[荣耀无障碍手册 2025](https://www.honor.com/content/dam/honor/cn/honor-esg/technology_better/imgs/pdf/%E6%97%A0%E9%9A%9C%E7%A2%8D%E6%89%8B%E5%86%8C2025.pdf)
2. 在列表中找 **已下载的服务 / 已安装的服务 / 已安装应用**（文案因版本而异），打开 Agent App（如 `AutoGLM Android`）并确认授权弹窗
3. 快捷方式（可选）：**无障碍 → 无障碍快捷方式**，音量上下键同按 3 秒（勿与 Agent 急停键冲突）
4. **不要**依赖系统「屏幕朗读」做自动化；屏幕朗读与全面屏手势在 MagicOS 10 上有已知交互问题（[俱乐部反馈](https://club.honor.com/cn/thread-30020264-1-1.html)）——Agent 用独立 AccessibilityService，避免与系统朗读叠加

**风险点（侧载）**：荣耀存在无障碍管控能力（开发者文档：[MagicGuard AccessibilityControl](https://developer.honor.com/cn/docs/11011/reference/accessibility-control)）。侧载 APK 若无法出现在无障碍列表或无法开启：
- 优先改从 **荣耀应用市场 / 可验证签名渠道** 安装调试包；或
- 开 **开发者选项 + USB 调试**，用 `adb install -r` 后再进无障碍页；仍不行再查 MagicGuard / 纯净模式是否拦截

### 2.2 自启动 / 后台活动（必做）

官方保活说明：[应用后台被重启](https://www.honor.com/cn/support/content/zh-cn00406916/)、[无法后台运行](https://www.honor.com/cn/support/content/zh-cn00428704/)

对 **MNN Chat** 与 **Agent 壳** 各做一遍：

| # | 动作 | 操作 |
|---|---|---|
| 1 | 应用启动管理 | 设置搜索 **「应用启动管理」** → 关掉该应用「自动管理」→ 勾选 **允许后台活动**（按需再勾自启动/关联启动） |
| 2 | 电池优化 | 设置搜索 **「电池优化」** → 筛选 **所有应用** → 该应用设为 **不允许** |
| 3 | 多任务加锁 | 打开 App → 最近任务 → **下拉卡片加锁**（[应用管理](https://www.honor.com/cn/support/content/zh-cn15886317/)） |
| 4 | 电池模式 | **设置 → 电池 → 电池使用模式** 用均衡/性能；测推理时关省电/超级省电 |
| 5 | 省电例外（可选） | 若常用省电模式：在省电设置里把两 App 加入 **例外/白名单**（仅省电时生效） |

### 2.3 截屏 / 媒体投影注意点

- 一期优先：`AccessibilityService.takeScreenshot`（Android 11+，本机 Android 16 满足）——与 [Open-AutoGLM-Android](https://github.com/xinzezhu/Open-AutoGLM-Android) 一致，通常 **无需** MediaProjection 用户每次点允许
- 若改走 MediaProjection：Android 10+ 需用户授权；Android 14+ 对投影有更多限制，MagicOS 可能叠加通知与时长限制——**尽量不要依赖常驻投影**
- 银行 / 支付 / 部分系统页可能标记 `FLAG_SECURE`，截图黑屏：节点树也读不全时 → **暂停并请求人工接管**
- 系统自带截屏手势/悬浮球与 Agent 无关；勿与 Agent 悬浮急停重叠布局

### 2.4 其他荣耀坑位（一期就要记）

- [ ] 通知栏常驻「推理中 / Agent 运行中」，降低被杀概率  
- [ ] 关闭对该 App 的「休眠清理 / 智能省电杀进程」类开关（若设置里有）  
- [ ] 测任务时关掉「智慧运存」激进回收场景干扰（可选对比实验）  
- [ ] 全面屏手势 vs 导航键：手势导航下，Agent 的 `Back` 用 `GLOBAL_ACTION_BACK`，勿模拟边缘滑动以免和系统手势抢  
- [ ] 安装来源：调试阶段允许「安装未知应用」；上架/分发再收敛签名与市场渠道  

---

## 3. 本机脑：MNN Chat / MNN-LLM（骁龙 8 至尊领先版）

### 3.1 第 0 周冒烟（不写 Agent 先跑通）

1. 安装 [MNN Chat](https://play.google.com/store/apps/details?id=com.alibaba.mnnllm.android.release) 或从 [MNN Releases](https://github.com/alibaba/MNN/releases) / 源码 [`apps/Android/MnnLlmChat`](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat) 自编译  
2. 按第 2.2 节做完保活  
3. 下载 **Qwen3-0.6B（W4）** → 再试 **1.7B / 4B**  
4. 后端对比表（同 prompt、同长度，记 tok/s、首字延迟、温度、是否杀进程）：

| 后端 | 预期（相对） | 本机动作 |
|---|---|---|
| **Hexagon** | Prefill 最快（官方 0.6B ≈ CPU 7.9×） | 优先；需 MNN ≥3.6.1 且带 Hexagon 构建 / 官方包支持 |
| **OpenCL** | Prefill 次之，decode 尚可 | Hexagon 不稳时主力 |
| **Vulkan** | 作对照 | 个别驱动/机型可能更稳或更慢 |
| **CPU** | Prefill 最慢，decode 有时最好 | 兜底；长时间对话可对比发热 |

官方同代基准设备即 8 Elite（OnePlus 等）；你这台领先版同属 SM8750 / Hexagon v79 一代，数字可作量级参考，**仍以本机实测为准**。

5. 打开 MNN Chat 的 **API 服务**（默认常在 `127.0.0.1:8080`，可改端口/鉴权）：
   - `GET /v1/models`
   - `POST /v1/chat/completions`（Bearer / api-key）
   - 同机 Agent 用 `http://127.0.0.1:8080`；电脑调试用手机局域网 IP 或 `adb forward tcp:8080 tcp:8080`  
   背景：[为 MNN 扩展 OpenAI API（PR #3607）](https://github.com/alibaba/MNN/pull/3607)

### 3.2 混合推理路由（一期约定）

| 场景 | 走哪 | 规则 |
|---|---|---|
| 简单规划、纯节点树决策 | 本机 MNN | 默认 |
| 复杂 GUI、图标密集、节点残缺 | 云端 AutoGLM / GUI-plus | 上传截图 + 结构化动作 |
| 支付 / 隐私页 | **不上云截图**；本机或人工 | 白名单包名强制本地或确认 |
| 本机 API 挂了 / OOM | 自动降级云端文本规划（可选）或停机提示 | 需用户可关「仅本机」开关 |

### 3.3 自编译注意（若不用 Play 包）

- NDK 与官方 README 对齐（常见 **27.x**），开 `MNN_OPENCL`、`MNN_BUILD_LLM`、16KB page size linker flags  
- Hexagon：按 [hexagon/README](https://github.com/alibaba/MNN/blob/master/source/backend/hexagon/README.md) 编 `v79` 的 `libMNN_htpops*.so`，设好 `ADSP_LIBRARY_PATH`  
- Android 16 上旧构建曾有页对齐崩溃类问题，**用新 NDK / 新 MNN**，勿拷很旧的 so

---

## 4. Agent 壳：Fork a11y 项目 → 换成 MNN 后端

### 4.1 推荐 Fork 源

| 仓库 | 用途 | 许可（以仓库 LICENSE 为准） |
|---|---|---|
| **[xinzezhu/Open-AutoGLM-Android](https://github.com/xinzezhu/Open-AutoGLM-Android)** | **首选壳**：纯无障碍、无 ADB、已有 tap/swipe/type/back、模型配置可换 | README 写明 **MIT** |
| [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | 协议 / 动作格式 / 敏感确认思路参考；默认 **ADB**，不适合作随身常驻 | Apache-2.0 |

> 官方 Open-AutoGLM 是电脑 ADB 控机；你要的是「手机版 Grok Bot」，故 **以 Android a11y Fork 为主**，官方仓只借协议与安全设计。

### 4.2 必改工程项（清单）

- [ ] Fork 到自己的 GitHub，包名改为自有（避免与原版无障碍服务名冲突）  
- [ ] `model` 配置增加：**MNN Local**  
  - Base URL：`http://127.0.0.1:8080/v1`  
  - Model：`mnn-local`（或 `/v1/models` 返回名）  
  - API Key：与 MNN Chat 设置一致  
- [ ] 保留云端配置：智谱 `autoglm-phone`、百炼 `gui-plus`（混合）  
- [ ] 路由层：`local_text` / `cloud_gui` / `local_only` 三模式  
- [ ] 感知：默认 **节点树 JSON**；树置信度低再 `takeScreenshot` → 云端 VLM  
- [ ] 动作执行：继续用现有 `AccessibilityService` + `GestureDescription`；坐标按 2800×1264 校准  
- [ ] 输入：优先 `ACTION_SET_TEXT` / paste；必要时自定义 IME（原项目已有 IME 路径）  
- [ ] 前台服务 + 通知；对接第 2.2 保活  
- [ ] 急停：悬浮球 / 通知一键 `cancel` + `disableSelf`（临时停手）  
- [ ] 日志：每步 `appId / activity / action / backend(local|cloud) / latency`  

### 4.3 刻意不做（一期）

- 不从零写 Agent 框架  
- 不绑定电脑 ADB 做日常路径  
- 不把麒麟 / EMUI 适配写进里程碑  
- 不在一期做应用商店上架与完整 GUI 小模型端侧部署  

---

## 5. 感知 / 动作 MVP 范围（一期「通用」）

### 5.1 感知

| 优先级 | 方式 | 用途 |
|---|---|---|
| P0 | 无障碍节点树（text / contentDescription / bounds / clickable） | 列表、按钮、输入框 |
| P1 | 前台包名 + Activity | 启动目标 App、安全策略 |
| P2 | `takeScreenshot` | 节点残缺、图标按钮、游戏化 UI |
| P3（二期） | 本机小 VLM / GUI 模型经 MNN | 减少上云 |

### 5.2 动作白名单（仅这些）

- `launch` / `tap` / `long_press` / `double_tap`  
- `swipe`（上下左右滚动）  
- `type` / `clear`  
- `back` / `home` / `recents`（慎用）  
- `wait`  

### 5.3 一期验收 App 集合（通用，非垂直脚本）

建议固定 6 个场景，覆盖不同 UI 工具包：

1. 系统设置（原生控件）  
2. 浏览器（滚动 + 搜索框）  
3. 应用市场或文件管理（列表）  
4. 笔记 / 备忘录（输入）  
5. 一个常用社交 App（聊天列表，**不自动发送**直到确认）  
6. 一个信息流 App（复杂滑动）  

---

## 6. 安全闸（必须同期交付）

- [ ] **敏感动作确认**：支付、转账、删除、卸载、改密码、授权新权限、发送消息 / 发帖 → 弹确认卡片，默认否  
- [ ] **敏感包名黑名单**（可配置）：支付宝 / 微信支付页 / 银行 / 系统纯净模式等 → 仅提示人工  
- [ ] **验证码 / 登录**：检测验证码控件或登录页 → `human_takeover`，停止自动点击  
- [ ] **急停**：悬浮按钮 + 通知 Action；触发后清空动作队列  
- [ ] **上云开关**：默认「截图可上云」；金融 / 相册 / 短信包名强制本地或禁止截图外传  
- [ ] **审计日志**：仅本机，可一键清空  
- [ ] **权限最小化**：只申请无障碍、网络、查询应用；不申请通讯录/短信除非二期明确需要  

对齐 Open-AutoGLM「敏感操作确认 / 人工接管」思路，但实现落在无障碍壳内。

---

## 7. 周计划（约 2–3 周 MVP）

### 第 0 周（1–2 天）：机型基线

- [ ] 记录本机关于页截图规格（已有）与 MagicOS 小版本  
- [ ] 装 MNN Chat，跑通 0.6B / 4B，填后端对比表  
- [ ] 打开 API，`curl`/`okhttp` 打通 `/v1/chat/completions`  
- [ ] 完成第 2.2 保活；灭屏 10 / 30 / 60 分钟后 API 是否仍在  

### 第 1 周：壳 + 手

- [ ] Fork Open-AutoGLM-Android，改包名，Debug 安装到 PPG-AN00  
- [ ] 打通无障碍开关（处理侧载 / MagicGuard 拦截）  
- [ ] 云端模型先跑通一条「打开设置并进入电池」  
- [ ] 接入 MNN Local，同一任务本机规划成功（可先弱视觉、强节点）  
- [ ] 急停 + 多任务加锁验证  

### 第 2 周：混合 + 安全 + 通用任务

- [ ] 实现 local/cloud 路由与失败重试  
- [ ] 节点树优先，截图回退  
- [ ] 敏感确认与人工接管  
- [ ] 跑完第 5.3 节 6 个场景各 ≥3 次  
- [ ] 发热 / 耗电 / 杀后台日记  

### 第 3 周（缓冲 / 打磨）

- [ ] 坐标与横竖屏、分屏、小窗  
- [ ] 输入法切换与中文输入稳定性  
- [ ] 整理「荣耀 MagicOS 10 保活步骤」用户向一页纸  
- [ ] 评估二期：MobiZen-GUI 类中文 GUI 小模型能否进 MNN  

---

## 8. 本机验收测试（Pass/Fail）

在 **PPG-AN00 / MagicOS 10.0.0.175** 上执行；每项记：后端、是否截图上云、耗时、是否被杀。

| ID | 用例 | 通过标准 |
|---|---|---|
| T1 | MNN 0.6B Hexagon/OpenCL/CPU 各一轮 | 三后端均能完成 ≥128 token；无崩溃 |
| T2 | MNN 4B 连续对话 10 轮 | 无 OOM；温度可接受；进程仍在 |
| T3 | 灭屏 30 min 后打本地 API | 200 + 正常补全，或明确「需打开 MNN Chat」提示 |
| T4 | 开启 Agent 无障碍 | 设置页显示已开启；服务 `onServiceConnected` |
| T5 | 「打开设置」 | 进入系统设置，≤3 步，无乱点 |
| T6 | 「在设置里找到电池」 | 进入电池相关页；允许一次滚动 |
| T7 | 浏览器打开并搜索关键词 | 搜索框输入正确 + 触发搜索 |
| T8 | 列表页连续上滑 5 次 | 内容变化；无点进广告死循环 |
| T9 | 笔记输入一段中文 | 文本与指令一致 |
| T10 | 社交 App「给某人发你好」 | **必须**弹出确认；点取消则不发送 |
| T11 | 支付页 / 银行（若有） | 自动停止并提示人工 |
| T12 | 急停 | 动作队列清空，不再点击 |
| T13 | 仅本机模式断网 | 节点任务仍可完成；需视觉的任务优雅失败 |
| T14 | 最近任务清后台（未加锁） | 记录失败；加锁后同任务应仍可恢复或明确要求重开 |

---

## 9. 关键链接速查

| 资源 | URL |
|---|---|
| MNN 主仓 | https://github.com/alibaba/MNN |
| MNN Chat 源码 | https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat |
| MNN Chat Play | https://play.google.com/store/apps/details?id=com.alibaba.mnnllm.android.release |
| MNN 3.6.1 / Hexagon | https://github.com/alibaba/MNN/releases/tag/3.6.1 |
| OpenAI API PR | https://github.com/alibaba/MNN/pull/3607 |
| a11y Agent 壳 | https://github.com/xinzezhu/Open-AutoGLM-Android |
| 官方 Phone Agent（ADB，仅参考） | https://github.com/zai-org/Open-AutoGLM |
| 荣耀无障碍说明 | https://www.honor.com/cn/support/content/zh-cn15869276/ |
| 荣耀后台保活 | https://www.honor.com/cn/support/content/zh-cn00406916/ |
| 荣耀后台无法运行排查 | https://www.honor.com/cn/support/content/zh-cn00428704/ |
| MagicGuard 无障碍管控（开发） | https://developer.honor.com/cn/docs/11011/reference/accessibility-control |

---

## 10. 下一步（你确认后可做）

1. **建私有 GitHub 仓**：Fork Open-AutoGLM-Android + 加 `docs/HONOR_PPG-AN00.md`（本文精简版）  
2. **第 0 周实测表**：你在手机上跑 MNN Chat 后端对比，把 tok/s 填回仓  
3. **不做**：现在不推正式 APK、不上应用市场、不扩华为机型矩阵  

---

*清单针对荣耀 PPG-AN00 / MagicOS 10；明确排除华为 EMUI / 麒麟适配。*

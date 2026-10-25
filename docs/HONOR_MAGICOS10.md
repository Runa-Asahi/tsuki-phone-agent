# 荣耀 PPG-AN00 / MagicOS 10 保活与无障碍

目标机：PPG-AN00，MagicOS 10（Android 16），骁龙 8 至尊领先版，12GB。

## 无障碍

1. **设置 → 辅助功能 → 无障碍**
2. 在「已下载的服务 / 已安装的服务」中打开 **荣耀手机 Agent**
3. 侧载若列表没有：开开发者选项 + USB 调试，`adb install -r app-debug.apk` 后再进无障碍页；仍不行查 MagicGuard / 纯净模式

## 保活（Agent 与 MNN Chat 各做一遍）

| # | 动作 | 操作 |
|---|---|---|
| 1 | 应用启动管理 | 设置搜索「应用启动管理」→ 关自动管理 → 允许后台活动 |
| 2 | 电池优化 | 搜索「电池优化」→ 所有应用 → 设为「不允许」 |
| 3 | 多任务加锁 | 最近任务 → 下拉卡片加锁 |
| 4 | 电池模式 | 均衡/性能；测推理时关超级省电 |
| 5 | 通知 | 保留「Agent 运行中」常驻通知 |

## 其他

- Back 用 `GLOBAL_ACTION_BACK`，勿模拟边缘滑动抢全面屏手势
- 一期截图优先 `AccessibilityService.takeScreenshot`，避免常驻 MediaProjection
- 分辨率 2800×1264：坐标按真实宽高；本 App dump 已带 screenWidth/Height


## 微信 / QQ 消息流程（v58eac8e 之后）

**为什么微信界面读到「(空)」**：微信 8.0.52+ 对无障碍服务做了白名单校验（按服务类名），非白名单服务拿到的节点树为空或被混淆。
这是微信侧行为，不是 Agent 配置问题（配置已是 `canRetrieveWindowContent=true`、`typeAllMask`、
`flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews|flagInputMethodEditor`，未设 packageNames）。

**本项目的做法**（不伪装系统服务）：
1. 先读无障碍树（所有应用窗口、refresh、最多轮询 3 秒）；
2. 文字少于 3 项 → `takeScreenshot` + 端侧 OCR（ML Kit 中文，模型打包在 APK 内，离线、不依赖 Google Play 服务）；
3. 输入文字：ACTION_SET_TEXT → 无障碍输入法通道（Android 13+，树被隐藏也能输入）→ 剪贴板粘贴；
4. 消息流程完全确定性、不调用模型：回到主界面 → 聊天列表唯一精确匹配 / 搜索 → **只点与联系人名完全一致且唯一的结果**
   （0 个或多个同名 → 停止并提示）→ **核对聊天标题** → 输入 → 核对输入框 → 确认框（显示收件人+内容）→ 点「发送」→ 核验气泡。
5. 读不到界面（树与 OCR 都为空）时直接失败，不让模型盲猜。

**排障**：主界面「导出诊断日志」会复制并分享本次任务日志（含每次观察的来源 tree/ocr 与文字数）。

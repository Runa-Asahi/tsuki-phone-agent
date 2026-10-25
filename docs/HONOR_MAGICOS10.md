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

# 荣耀手机 Agent（Honor Phone Agent）

面向 **荣耀 PPG-AN00 / MagicOS 10（Android 16）** 的本机手机 Agent：

- **脑**：本机 [MNN Chat](https://github.com/alibaba/MNN) OpenAI 兼容 API（默认 `http://127.0.0.1:8080/v1`）；可选云端（DeepSeek / OpenAI 兼容，API Key 存加密 SharedPreferences）
- **手**：`AccessibilityService` + 手势 / 输入法通道 / 剪贴板
- **眼**：无障碍节点树；微信等隐藏树时 → 截图 + **端侧 ML Kit 中文 OCR**（离线、无需 GMS）
- **安全**：发送/支付确认闸（无障碍浮窗）+ 敏感包名拦截 + 急停 + 空联系人拒绝

包名：`com.runasakura.phoneagent`（debug 为 `.debug`）  
许可：Apache-2.0  
仅针对荣耀 MagicOS，不覆盖华为 EMUI。分支开发请用 `feat/mvp-agent`，勿直接改 `main`。

## 功能一览

| 模块 | 状态 |
|---|---|
| 可见屏幕元素 dump（`[e#] 文本 (x,y) tap\|edit`） | ✅ |
| 确定性消息流程（微信/QQ：精确匹配、标题核对、确认后发送） | ✅ |
| 通用子目标：打开 App / 进入 / 点击 / 上滑下滑 / 返回 / 搜索 / 输入发送 | ✅ |
| OCR 兜底 + 缓存（动作后失效）+ PPG-AN00 手势条裁剪 | ✅ |
| 本机 MNN + 可选云端（DeepSeek / OpenAI 兼容 / hybrid） | ✅ |
| 通用搜索入口（购物 App 占位搜索栏） | ✅ |
| 自检 / 导出诊断日志 | ✅ |
| 端侧 GUI 小模型 | ❌ 二期 |


## 云端模型（DeepSeek / OpenAI 兼容）

设置页 → **云端提供商**：

| 选项 | Base URL | 默认模型 |
|---|---|---|
| 不使用云端 | — | — |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenAI 兼容 | 自填 | 自填 |

- API Key **只在设置页粘贴**，写入本机 `EncryptedSharedPreferences`（MasterKey AES-GCM）；诊断日志只显示遮罩（前4…后4）。
- 路由：`local_only`（默认）/ `local_first` / `hybrid`（本机失败再上云）/ `cloud_only`。
- 协议与本机 MNN 相同：OpenAI `POST /v1/chat/completions`。
- 截图上云默认关闭；金融类包名仍禁止。

## 安装（PPG-AN00）

1. 安装 MNN Chat，启动 API（`127.0.0.1:8080`），选用 **Instruct** 小模型（如 LFM2.5-1.2B / Qwen Instruct），勿用长思考 Reasoning 模型。
2. 安装本 Agent debug APK；**每次更新后**到 设置→辅助功能 里重新打开本服务。
3. 电池：允许「自启动 / 关联启动 / 后台活动」；最近任务可加锁。
4. 打开 App → **自检**（建议含打开微信/QQ）→ 全部通过后再下任务。

示例目标：

- `打开微信给文件传输助手发送你好`
- `打开QQ，进入我的电脑，发送你好`
- `打开设置并进入电池`
- `上滑，点击评论`（当前前台 App）

发送前会弹出确认框（收件人+内容）；点「导出诊断日志」可分享失败现场。

## 构建

```bash
git checkout feat/mvp-agent
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 + Android SDK 36。详见 [docs/HONOR_MAGICOS10.md](docs/HONOR_MAGICOS10.md)、[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。

## 安全约定

- 永不在未确认时点击「发送 / 确认支付 / 转账」
- 联系人或消息为空直接拒绝
- 搜索结果必须**唯一精确匹配**联系人名；0 个或多个同名 → 停止
- 进入聊天后核对标题；不符则退出且不输入

## v0.2.0-mvp 新增

- **路由修复**：`仅云端` 不再访问本机 MNN（含健康检查）；路由模式每步实时读取；已填 URL/Key 但提供商为「不使用云端」时自动识别（cloud_only 失效的根因）。
- **本机卡顿自动兜底**：本机 MNN 15 秒无响应（MNN Chat 在后台被 MagicOS 限速）即本步改用云端；自检对话框提供「MNN 后台设置」一键跳转。
- **MNN 健康检查**：`/v1/models` 单飞 + 30 秒缓存。
- **云端调用优化**：共享连接池、temperature 0、max_tokens 200、DeepSeek/OpenAI JSON 模式、429/5xx 退避重试一次、延迟与 token 估算统计；设置页「测试连接」。
- **本机/云端协作**：简单步骤走本机；输出无效或界面连续无变化时升级到云端；任务结束输出模型统计。
- **微信语音/视频通话**：「给X打语音电话 / 和X视频通话」，确认浮窗显示对象与通话类型后才拨出，并校验通话界面。
- **备注前缀联系人**：搜索结果「12345678王小明」在联系人/最常使用分区唯一时可选中，确认框显示完整名称。
- **提示注入防护**：屏幕文字作为不可信数据包裹；只允许输入用户目标里的文字；记录「疑似注入」。
- **设置直达**：「打开蓝牙设置 / 查看电池」等只读设置页直接打开（不改任何开关）。

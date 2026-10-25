# Agent System Prompt / 动作 JSON Schema

模型每次只返回 **一个** JSON 对象：

```json
{
  "thought": "简短中文推理",
  "need_screenshot": false,
  "action": {
    "type": "launch|tap|long_press|double_tap|swipe|type|clear|back|home|recents|wait|done|fail|ask_confirm|human_takeover",
    "package": "com.android.settings",
    "app": "设置",
    "x": 100,
    "y": 200,
    "node_id": "n12",
    "text": "电池",
    "x1": 500, "y1": 1500, "x2": 500, "y2": 400,
    "duration_ms": 300,
    "ms": 800,
    "summary": "已完成",
    "reason": "需要确认发送"
  }
}
```

## 示例

打开设置：
```json
{"thought":"启动系统设置","action":{"type":"launch","package":"com.android.settings"}}
```

点击电池：
```json
{"thought":"点击电池入口","action":{"type":"tap","text":"电池"}}
```

发送消息前确认：
```json
{"thought":"即将发送，需用户确认","action":{"type":"ask_confirm","reason":"即将发送消息给联系人"}}
```

完成：
```json
{"thought":"已进入电池页","action":{"type":"done","summary":"已打开设置-电池"}}
```

运行时完整 prompt 见 `SystemPrompt.kt`（与本文一致）。

# DiPlay 0.2.11 — 2026-10-04 更新

## 本次更新

- HUD 投影会列出车机当前开放的副屏，可选择自动匹配或手动指定目标屏幕，并记住选择。
- HUD 诊断会记录悬浮窗权限、所选屏幕、当前连接屏幕和可用副屏，便于定位无显示问题。
- 无线连接可为下一次连接选择 Wi-Fi Direct 信道；“自动”仍是默认选项。
- 仪表地图可使用可调位置和大小的转向提示卡。
- 可选择两指、三指或四指下滑打开 DiPlay 设置。
- 增加高级车辆数据设置和受支持车机的自动热点启动选项。
- 改善 Android 9 音频兼容性、连接恢复、媒体更新和诊断信息。

## 吉利车机适配

- 沿用 GD 已验证的 Android 副屏投影方式，不再要求副屏名称必须包含“HUD”。
- 保留自定义 CarPlay 返回桌面图标、导航独立通道、音频焦点和蓝牙音乐交接。
- 保留媒体方控、原厂语音键兼容模式和方向盘按键识别。
- 用户可填写故障并主动将脱敏诊断报告上传云端；单份报告上限为 1 MiB。

本次合并沿用上游版本号 **0.2.11 / code 30**。HUD 投影仍要求车机把对应副屏开放给 Android；多副屏车机请在设置中选择实际 HUD 屏幕。G636、FX11 和 KX11 的最终效果仍需实车复测。

## What’s new

- HUD projection lists active secondary displays, supports automatic or manual selection, and remembers the target.
- HUD diagnostics record overlay permission, the selected and attached screens, and all available secondary displays.
- Choose a preferred Wi-Fi Direct channel for the next connection while keeping Auto as the default.
- Use a movable, resizable dashboard turn card and a configurable two-, three- or four-finger settings gesture.
- Add advanced vehicle-data settings, optional hotspot startup on supported head units, Android 9 audio compatibility improvements, connection recovery and richer diagnostics.

## Geely adaptation

- Use the GD secondary-display projection path without requiring the display name to contain “HUD”.
- Retain the custom CarPlay home icon, separate guidance channel, audio focus and Bluetooth music handoff.
- Retain media steering controls, factory voice-key compatibility and steering-button identification.
- Let the user describe a problem and explicitly upload a redacted diagnostic report to the cloud, limited to 1 MiB per report.

This merge keeps the upstream version **0.2.11 / code 30**. HUD projection still requires the head unit to expose the target as an Android secondary display. G636, FX11 and KX11 behavior still needs an installed vehicle retest.

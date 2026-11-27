# 技术架构

## 媒体链路

```text
OPPO Car+ / 荣耀车联 / CarLife+ 增强版 CarLife 发送端
  -> 本机 CarLife 车机端口 7240/8240/9240/9241/9340
  -> H.264 解码与应用内控制画面
  -> 车机上报分辨率 H.264，默认等比完整显示
  -> CFMOTO EasyConn / PXC
  -> 450MT MotoPlay
```

荣耀路线必须从荣耀车联的“无线 WLAN 连接”页点击“开始连接”。骑投不能直接启动
`CarlifeActivity` 作为替代路径，因为该启动方式只会输出 CarLife+ 欢迎页，尚未创建
荣耀 CarConnect 的真实车联展示与投送 Surface；loopback 协议仍能协商视频，但内容源
不是最终桌面。

荣耀流程还要求先完成系统蓝牙连接。推荐直接连接 CFMOTO 车机蓝牙；即使声音输出选择
手机，荣耀车联建立 CarLife 连接时仍可能依赖已连接的蓝牙链路。

## 主要模块

- `CarPlusVideoReceiver`：接收手机侧车联应用暴露的 CarLife 视频和音频数据。
- `MiniVideoPipeline` / `AaCompositor`：解码、缩放、合成并重新编码投送画面。
- `MotoAudioPlayer`：播放媒体和导航 PCM，并处理手机或车机 A2DP 路由。
- `MotoWifi`：通过 `WifiNetworkSpecifier` 连接和恢复仪表热点。
- `MotoLink`：处理 CFMOTO EasyConn / PXC 握手、心跳和投送连接。
- `BluetoothA2dpKeeper`：检查已配对的 CFMOTO A2DP，并尝试恢复连接。
- `CarPlusBridgeService`：承载前台服务、连接协调和息屏保活。

## 显示与音频

- 源画面分辨率以手机车联实际送流为准，并在变化时同步更新合成参数。
- 正常投送等待车机通过 PXC 上报真实画布尺寸后再创建编码器，不在连接前写死 450MT 尺寸。
- 默认采用等比完整显示（`FIT`），不同宽高比的车机只产生黑边，不裁掉左右内容。
- 每个已保存车辆可独立配置 `FIT`、`FILL` 或 `STRETCH`，并可设置上、下、左、右安全边距；安全区外的编码画布保持黑色。
- PXC `capture` 中的尺寸、FPS、编码器字段和扩展标志会参与编码协商；未知控制/媒体命令与原始配置片段写入脱敏诊断日志。
- CarLife 源分辨率请求与车机输出分辨率相互独立，只允许使用预置的偶数尺寸；实际码流仍以 SPS/MediaCodec 上报为准。
- 输入解码按硬件优先顺序尝试并在启动失败时回退；输出编码优先硬件 Surface 编码器，H.264 Level 与码率按尺寸/帧率计算。
- 手机内两张预览最多刷新 20 FPS，车机编码输出不受预览限帧影响。
- 音乐与导航使用独立 `AudioTrack`。
- 默认跟随手机媒体路由；用户可显式选择车机 A2DP。
- A2DP 不可用时不会静默回退，避免声音意外从手机扬声器播放。

## 权限

- Android 13+：附近 Wi-Fi、通知，以及扫码时需要的相机权限。
- Android 12+：车机蓝牙所需的 `BLUETOOTH_CONNECT`。
- Android 12 及以下：Wi-Fi 网络连接所需的定位权限。
- Android 11 及以下：旧版蓝牙权限。

当前 `targetSdk` 为 36。后续升级到 `targetSdk` 37 时，需要按 Android 17
本地网络权限要求加入并运行时申请 `ACCESS_LOCAL_NETWORK`。

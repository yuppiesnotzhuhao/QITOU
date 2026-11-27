# 技术架构

## 媒体链路

```text
OPPO Car+ / 荣耀车联 / CarLife+ 增强版 CarLife 发送端
  -> 本机 CarLife 车机端口 7240/8240/9240/9241/9340
  -> H.264 解码与应用内控制画面
  -> 800x384 H.264，底部保留 80 px 仪表安全区
  -> CFMOTO EasyConn / PXC
  -> 450MT MotoPlay
```

## 主要模块

- `CarPlusVideoReceiver`：接收手机侧车联应用暴露的 CarLife 视频和音频数据。
- `MiniVideoPipeline` / `AaCompositor`：解码、缩放、合成并重新编码投送画面。
- `MotoAudioPlayer`：播放媒体和导航 PCM，并处理手机或车机 A2DP 路由。
- `MotoWifi`：通过 `WifiNetworkSpecifier` 连接和恢复仪表热点。
- `MotoLink`：处理 CFMOTO EasyConn / PXC 握手、心跳和投送连接。
- `BluetoothA2dpKeeper`：检查已配对的 CFMOTO A2DP，并尝试恢复连接。
- `CarPlusBridgeService`：承载前台服务、连接协调和息屏保活。

## 显示与音频

- 源画面按 1280x480 接收，输出画布为 800x384。
- 输出画面底部保留 80 px 安全区，避免遮挡 450MT 仪表区域。
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

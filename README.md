<p align="center">
  <img src="docs/images/app-icon.svg" width="112" alt="骑投 QITOU 应用图标">
</p>

<h1 align="center">骑投 QITOU</h1>

骑投（QITOU）是一个 Android 手机端桥接工具，用于将 OPPO Car+ / CarLife
画面和声音投送到 CFMOTO 450MT 的 MotoPlay 仪表。

项目不依赖 Android Auto、Google 地图、CarPlay、Shizuku、Root、无障碍服务
或悬浮窗。

## 下载

请前往 [GitHub Releases](https://github.com/yuppiesnotzhuhao/QITOU/releases/latest)
下载最新 APK 和 SHA-256 校验文件。

## 系统要求

- Android 10 及以上
- 手机安装并登录 OPPO Car+
- 摩托车仪表支持 CFMOTO MotoPlay
- 使用车机蓝牙声音时，需要提前在系统蓝牙中配对车机

## 流体云

Android 16 及以上、ColorOS 16 及以上系统会尝试将投送前台通知提升为系统实时活动，
在支持的 OPPO 设备上以流体云胶囊或卡片显示“连接中”“投送中”“重连中”等状态。

流体云由系统控制，应用不会额外创建第二个通知。Android 10～15、非 ColorOS 设备或
关闭了应用实时通知/流体云时，会继续显示普通前台通知，不影响投送功能。相关开关
通常位于“设置 → 通知与控制中心 → 流体云”。

## 界面预览

| 手机控制画面 | 深色模式 |
| :---: | :---: |
| <img src="docs/images/main-light.png" width="320" alt="骑投浅色主界面"> | <img src="docs/images/main-dark.png" width="320" alt="骑投深色主界面"> |

| 声音与连接 | 关于与项目信息 |
| :---: | :---: |
| <img src="docs/images/audio-light.png" width="320" alt="骑投声音与连接设置"> | <img src="docs/images/about-light.png" width="320" alt="骑投关于页面与 GitHub 项目信息"> |

## 主要功能

- 将 OPPO Car+ 画面桥接到 CFMOTO MotoPlay。
- 支持二维码配对、手动 Wi-Fi 配对和断线自动重连。
- 可选在每次打开应用时自动连接上次保存的车机，默认关闭。
- Android 16+ / ColorOS 16+ 支持以标准 Live Updates 显示流体云投送状态。
- 主连接入口保持首屏可见，音频和高级工具采用折叠布局。
- 应用内提供手机控制画面、触控转发和横屏全屏模式。
- 连接日志按 3 个 1.5 MB 文件轮转，导出 ZIP 会包含全部日志和最近崩溃记录。
- 音乐与导航独立音量，导航播报时可自动压低音乐。
- 声音默认跟随手机；也可手动切换到车机蓝牙 A2DP。
- 支持息屏保活、前台通知和日志导出。
- 支持 Android 17 深色、浅色主题。

## 首次使用

1. 在摩托车仪表上打开 MotoPlay / 手机互联二维码页面。
2. 打开骑投，点击“扫码连接车机”。
3. 按系统提示授权附近设备、通知和相机权限。
4. 骑投连接仪表 Wi-Fi 后会自动打开 OPPO Car+。
5. 在 OPPO Car+ 中选择“CarLife 无线连接”并点击“连接”。
6. 保持仪表停留在 MotoPlay 页面。
7. 在手机上使用“手机控制画面”或“全屏控制”操作 Car+。

车机蓝牙权限只会在选择“车机蓝牙”时申请。拒绝蓝牙不会阻止投送，应用会继续
使用手机音频。

## 声音输出

- “手机”是默认选项，跟随 Android 当前媒体输出，可能使用扬声器、有线耳机
  或已连接的蓝牙耳机。
- “车机蓝牙”只使用已配对的 CFMOTO A2DP，未连接时不会静默回退。
- 音乐和导航音量在应用内分别调整。

## 常见问题

### 息屏后连接断开

打开骑投的“息屏保活”，并在 ColorOS 中允许应用后台运行。部分 OPPO 系统版本
仍可能限制后台网络，需要将骑投加入电池优化白名单。

### 有画面但没有声音

确认当前的声音输出选择。使用手机音频时检查 Android 当前媒体输出设备；
使用车机蓝牙时确认 CFMOTO A2DP 已连接并授予附近设备权限。

### 为什么地图不是高德或百度

骑投只负责传递 OPPO Car+ 的画面和声音，不接入地图 SDK。实际地图和导航能力
由 OPPO Car+ 及手机中安装的地图应用提供。

### 日志会占用多少空间

应用保留 3 个轮转日志，每个上限约 1.5 MB，并保留最近一次 Java/Kotlin 崩溃记录。
导出的诊断 ZIP 包含全部轮转日志、设备摘要和崩溃记录。常规日志与导出缓存合计通常
不超过约 10 MB，不需要手动清理。

## 已知限制

- 仪表交互仅通过手机完成，不依赖摩托车触摸屏或车把按键。
- MotoPlay / PXC 为互操作性实现，后续仪表固件可能改变协议。
- 车机蓝牙和本地网络行为可能受 ColorOS 或手机厂商策略影响。
- 流体云仅在支持 Android 实时活动的系统上显示，具体样式由 ColorOS 决定。
- 骑投无法绕过 ColorOS 首次连接 Wi-Fi 时的系统确认界面。
- 日志会脱敏 Wi-Fi 密码和配对信息，但分享前仍建议检查内容。
- 本项目与 OPPO、CFMOTO、Google 无隶属或官方合作关系。

## 开发与构建

构建要求、Release 签名配置和输出路径见
[docs/BUILDING.md](docs/BUILDING.md)。

内部媒体链路、PXC 和端口说明见
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 致谢

感谢 [CarWithin 项目](https://github.com/xmz28/CarWithin) 及作者
[xmz28](https://github.com/xmz28)。CarWithin 的 iOS + CarPlay/CarLife
互联方案为骑投的产品设计和协议研究提供了启发。

骑投还包含或改编了 OpenCfMoto、CarPToCarL、CarVeX 等开源项目。完整声明请查看
`NOTICE` 和 `app/src/main/assets/licenses/`。

## 开源许可证

骑投以 GNU Affero General Public License v3.0 only
（`AGPL-3.0-only`）开源。完整协议见 [LICENSE](LICENSE)。

分发修改版时，必须保留版权和许可声明，并按照 AGPL 提供 Corresponding Source。

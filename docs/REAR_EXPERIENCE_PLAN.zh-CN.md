# 背屏扩展项目：原生能力与实施方案

调查日期：2026-10-28。开发分支：`feature/rear-experience`。

基于 [killerprojecte/REAREye](https://github.com/killerprojecte/REAREye) 的 `dev`，起点为
`c081c8c1a428eb5fa812cee4f594046bd323a148`。保留上游 GPL-3.0 许可证和作者归属。

本分支目前完成项目调查和方案，尚未实现或安装新增功能。

## 原生能力：不止 QQ、微信和音乐

调查对象为 Xiaomi 17 Pro Max、Android 17、HyperOS `OS4.0.5.0.XPBCNXM`。
当前背屏中心版本为 `RELEASE-1.0.2609231642`，versionCode `426092316`。
下面来自该版本 APK 的原生资源注册表、模板目录和通知处理代码。
**注册与模板存在说明系统具备入口，不代表已逐个触发并验证应用的实际显示。**

| 场景 | 原生注册的应用或来源 | 能力边界 |
| --- | --- | --- |
| 电话 | 系统通话界面 | `incall` / `phone` 模板 |
| 时钟 | 系统时钟 `com.android.deskclock` | 已有 `alarm`、`countdown`，对应闹钟和倒计时；不能据此认定完整时钟应用、秒表均已适配 |
| 音乐 | 小米音乐、QQ 音乐、网易云、汽水、酷狗、酷我等 | `music`，仍依赖有效媒体会话与系统开关 |
| 国际音乐 | Spotify、YouTube Music、Yandex Music、Shazam、Amazon Music、Apple Music、Deezer、Tidal、JOOX、KKBOX、Zing MP3、AWA、LINE MUSIC | 同一 `music` 模板；注册表含包名，不等于每个版本均能正常控制 |
| 打车 | 滴滴、高德、百度地图 | `carHailing`；指打车状态，不等于地图导航全过程 |
| 外卖 | 美团、美团外卖、饿了么、淘宝、京东 | `foodDelivery`；指适配的订单通知，不等于所有订单、购物通知 |
| 车辆 | 小米汽车 | `xiaomiev` / `ev` |
| 家庭摄像头 | 米家 | `mihomeCamera` / `miHomeCamera` |
| 行程与股票 | 智能助理相关来源 | `travel`、`stock`；部分内容由应用侧提供布局 |
| 电影、记忆、家居 | `com.xiaomi.aicr`、小爱、`com.xiaomi.mihomemanager` | `movie`、`memory`、`mishow`；需对应系统服务和内容 |
| 体育赛事 | 腾讯体育、哔哩哔哩、直播吧、懂球帝 | `sports_schedule`，并非这些应用的所有通知 |
| 快递 | 菜鸟 | `Logistics`，通用布局模板 |
| 共享单车 | 哈啰 `com.jingyao.easybike` | `bike_sharing`，通用布局模板 |
| 停车 | 支付宝 | `parking`，通用布局模板 |
| 日程 | 系统日历 `com.android.calendar`、`com.xiaomi.calendar` | `schedule_reminder` |
| Android Live Updates | 支持系统实时通知协议的应用 | 已有 `live_update_call`、`live_update_progress`、`live_update_big_text` |
| 录音 | 系统录音机 | 当前注册表和内置模板中未找到录音专用条目，需要新增适配 |

证据位置（仅在设备 APK 的本地提取副本中，不随本项目分发）：

- `res/xml/sub_screen_app_registry.xml`：包名、business、分组；还有唱吧 `com.changba.backscreen.action.VIEW` 应用卡片入口。
- `assets/smart_assistant/`：电话、闹钟、倒计时、音乐等 16 个模板资源。
- 本版本反编译的 `p065m2.a`：内置业务到模板的映射，以及时钟多业务映射。
- `SmartAssistantNotificationListenerService`：区分带 `miui.focus.param` / `miui.rear.param` 的通知与 Android promoted ongoing 通知。
- 本版本反编译的 `p065m2.b`：Live Updates 的 `CallStyle`、`ProgressStyle`、`BigTextStyle` 转换。

普通消息提醒、原生业务卡片、把完整应用搬到背屏是三种不同能力。
QQ、微信显示消息并不能推导出所有第三方焦点通知都具备专用背屏布局。
Live Updates 也要求满足 promoted ongoing 等条件，不能将普通 ongoing 通知一律视为受支持。

## 为什么以 REAREye 为基础

上游已经具有以下基础设施，无需另建一套高权限显示服务：

- `RearWidgetHook`：业务注册、模板映射、scene 路由、卡片注入、更新和移除。
- `SystemUiNotificationBridgeHook` 与 `NotificationRouteBridge`：从 SystemUI 桥接普通通知。
- `RearWidgetApiContract`：组件接口。
- MAML 渲染、模板变量、常驻卡片和壁纸管理。
- `MusicControlWhitelistModule`：允许更多标准媒体会话复用原生音乐卡片。

当前存在两个具体缺口：

1. `allowSelfDescribedNotificationPackage()` 的“允许焦点通知”开关只放行能够解析出有效 business 的通知，还会排除一组内部业务。它不会自动生成缺失模板，也不会自动将主屏焦点布局缩成背屏布局。
2. `NotificationRouteSnapshot.fromStatusBarNotification()` 默认会跳过带 `miui.focus.param` 或 `miui.rear.param` 的通知。当前快照主要携带标题、正文等文本，不足以通用复刻进度、倒计时和可操作按钮。

所以“全部焦点通知”应实现为：**保留原生适配 + 已知场景专用适配 + 未知场景通用兜底**。
不能直接删除参数过滤，也不能全局放开所有业务后假定已有界面可用。

## 内容与界面设计

### 焦点通知

统一解析为内部展示模型：稳定通知标识、应用、图标、标题、正文、进度、计时基准、支持的操作、可见性。

- 有可用原生 business 和模板时沿用原路径，避免生成重复卡片。
- 已知结构的 `param_v2`、旧版焦点协议分别解析；不认识的结构降级为文本卡片。
- 通用模板提供“大标题 + 两行内容”“进度 + 状态”“计时 + 两个操作”三类布局。
- 更新按 notification key 合并，移除通知时同步撤卡；单独处理分组摘要、多用户和进程重建后的恢复。
- 操作只调用源通知实际提供的 `PendingIntent` / `Notification.Action`；不能伪造录音暂停、订单操作等能力。
- 保留锁屏隐私、工作资料和通知隐藏规则，不额外展示系统已隐藏的正文。

### 时钟与录音

- 首先复用已存在的闹钟和倒计时卡片。
- 常驻数字时钟是独立模板需求，可复用 MAML 日期变量；秒表需要单独确认状态来源。
- 录音第一版显示正在录音、已用时和状态。优先读取源通知的标准时间字段与操作，暂停/继续/停止仅在源通知提供对应操作时展示。
- 若系统录音机通知缺少必要状态，再针对该版本录音机做局部 Hook；不在背屏界面另开一个麦克风录音服务。
- 暂停期间冻结时长，恢复后继续累计，禁止仅凭通知发布时间假设录音一直在运行。

### 绿色液体充电显示

视觉目标为黑底、绿色液体、双层缓慢波浪、清楚的百分比数字。

找到的[视频参考](https://www.youtube.com/shorts/EhaUVXlZbhE)同时展示小米 11 Ultra 和小米 17 系列外观的设备，
可见绿色电量填充效果。该视频不是官方资源，不能证明特定效果一定是小米 11 Ultra 的出厂动画，
也未证明水位始终精确对应真实电量。这里采用原创绘制，不搬运视频或厂商私有动画素材。

电量比例 `p = clamp(level / scale, 0, 1)`；可显示区域高度为 `H`：

```text
液面平均高度 y = H × (1 - p)
30% -> 从底部填充 30% 的高度
80% -> 从底部填充 80% 的高度
```

- 插电时从底部平滑升到当前电量，后续跟随真实电量变化；不能每次接电都循环灌满到 100%。
- 先处理未知 level / scale，不把无效数据画成 0%；100% 时完整填满，波幅在接近两端时收敛。
- 竖向指用户实际观看方向的上下，与原始显示缓冲区坐标分开。
- 本次设备逻辑背屏为 display 1，原始尺寸 `976 × 596`、density 450、左侧存在 `296 × 596` cutout。实际实现读取当前 display、rotation、WindowInsets 和圆角，禁止硬编码整个矩形都可显示。
- 充电状态来自 `ACTION_BATTERY_CHANGED` / `BatteryManager`，区分供电、正在充电、充满、暂停充电和断电。电池保护暂停充电时需正确标识。
- 屏幕关闭、卡片不可见或拔电后停止逐帧绘制。亮屏时限制帧率，避免为了波浪维持背屏常亮。
- 优先作为原生卡片或 MAML 模板集成。若模板能力不足再用原生 Canvas View；全屏覆盖层只在确认背屏窗口生命周期后接入。
- 原生背屏充电页面的具体所属进程和挂载点尚未完全定位，不能直接用主屏 `MiuiChargeController` 当作背屏入口。

## 实施顺序

1. 扩展通知快照与路由模型，加入版本分离的焦点协议解析、去重和通用文本兜底模板。
2. 加入进度与计时模板，接入真实通知操作，保留原生业务优先级。
3. 复用原生时钟卡片，再加入录音专用适配；补常驻时钟和秒表状态源。
4. 定位背屏充电挂载点，完成按电量升降的液体绘制及屏幕生命周期联动。

各项独立开关，默认不替换原生卡片。针对背屏中心版本定位 Hook，找不到匹配点时停用该项扩展，避免影响整个背屏中心。

## 当前交付范围

- 已 fork 到 `ziyuan-404/REAREye` 并建立开发分支。
- 已读取当前连接设备的系统版本、显示信息、原生背屏中心 APK 和模板目录。
- 已检查上游源码的通知桥接、焦点放行、音乐白名单和卡片接口。
- 尚未编写新增功能、编译新增 APK、安装 APK、修改 LSPosed 作用域或变更手机设置。
- APK、反编译源码、设备日志、通知正文、序列号及凭据均不进入仓库。

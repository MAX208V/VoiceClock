# VoiceClock · 定时播报时间天气节气

基于 [jackjones2020/VoiceClock](https://github.com/jackjones2020/VoiceClock)（MIT）二次开发。

在原「中文语音报时」基础上增加了 **天气（自动定位）** 与 **二十四节气** 播报，
形成「时间 + 天气 + 节气」三合一的定时语音播报。

## 功能

- **定时播报**：按星期与每日时段，在整点／半点／自定义间隔播报
- **语音内容**：`现在时间，11月20日 星期五 下午1点1分。天气，多云，气温 12 摄氏度。明天是小雪。`
- **天气**：Open-Meteo 免费接口，无需 API Key，按经纬度查询
- **自动定位**：前台粗定位（`ACCESS_COARSE_LOCATION`），优先取系统最近位置，失败用缓存坐标
- **节气**：本地天文算法计算二十四节气，无需联网
  - 今日交节 → 「今天是立冬」
  - 明日交节 → 「明天是小雪」
  - 其它 → 「距离冬至还有 32 天」
- **降级策略**：天气获取失败或未授权定位时，仍正常播报时间与节气，不会静默
- **锁屏可靠**：`AlarmManager.setAlarmClock` + 前台服务 + 短暂唤醒锁

## 构建

GitHub Actions 自动构建（`.github/workflows/build.yml`）：

- 推送到 `main` 自动触发
- 运行单元测试 `./gradlew testDebugUnitTest`
- 构建 APK `./gradlew assembleDebug`
- APK 作为 artifact `voiceclock-debug-apk` 供下载（保留 30 天）

本地构建：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
# 输出 app/build/outputs/apk/debug/app-debug.apk
```

环境：JDK 17、Android SDK 35、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.10，minSdk 26。

## 项目结构

| 文件 | 职责 |
|---|---|
| `SolarTerms.kt` | 二十四节气天文计算（Meeus 太阳视黄经），含 UT→本地时区换算 |
| `WeatherClient.kt` | Open-Meteo 天气查询与 WMO 代码中文化 |
| `LocationHelper.kt` | 前台粗定位，取最近已知位置或单次更新 |
| `BroadcastText.kt` | 播报文案拼装（时间／天气／节气） |
| `Settings.kt` | 设置持久化（排程、天气、定位、节气开关与缓存） |
| `ClockService.kt` | 前台服务、TTS 播放、后台取天气、超时兜底 |
| `ClockAlarms.kt` | 系统闹钟调度 |
| `MainActivity.kt` | 设置界面、天气／节气预览、完整播报试听 |
| `Schedule.kt` | 星期／时段／间隔排程计算 |

## 测试

单元测试 33 个，覆盖：

- **节气算法**：与 **USNO（美国海军天文台）** 公布的 2026／2027 年四季时刻比对，
  误差均 ≤ 20 分钟（实测 1–9 分钟）
- **时区换算回归**：2026 冬至 UTC 12-21 20:50 → 北京时间 12-22 04:50（防止错一天）
- **天气解析**：Open-Meteo JSON 解析、缺字段容错、WMO 代码映射
- **文案拼装**：时段用词（凌晨／上午／中午／下午／晚上）、句末号防连读、缺项跳过
- **排程逻辑**：跨午夜、星期边界、模式对齐（原项目测试保持通过）

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 查询天气 |
| `ACCESS_COARSE_LOCATION` | 自动定位（仅前台，可拒绝） |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `SCHEDULE_EXACT_ALARM` | 准点唤醒 |
| `FOREGROUND_SERVICE*` / `WAKE_LOCK` | 播报期间保持运行 |

## 已知限制

- 需要系统装有中文 TTS 引擎与语音包
- 部分厂商系统需手动允许「后台运行」「自启动」并关闭电池优化
- 定位仅在前台请求，长期后台不刷新位置；坐标按 3 小时过期策略更新，

## 许可

MIT（继承上游）。上游作者：jackjones2020。

## CI 诊断分支

`ci-report` 分支由 CI 自动推送，仅存放构建日志与测试报告摘要，便于排查失败；可随时删除。

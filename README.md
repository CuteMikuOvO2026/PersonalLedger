# PersonalLedger（轻账）

个人本地离线记账 App，基于 Kotlin + Jetpack 实现，无需账号体系与网络，数据保存在设备端。

## 功能

- **记账**：记录收入 / 支出，支持金额、分类、备注与时间。
- **账单管理**：编辑、删除（带撤销）、按备注关键词搜索，以及按类型 / 分类 / 金额区间 / 日期区间筛选。
- **预算**：设置本月预算，根据当月支出实时计算进度，进度条颜色随使用率变化（80% 预警、100% 超支）。
- **报表**：支出分类占比饼图、最近 7 天每日支出柱状图、资产变化趋势折线图，并生成报表摘要。
- **数据导出**：导出 CSV（带 UTF-8 BOM，兼容 Excel）、导出 PDF、JSON 备份导出与导入恢复。
- **自定义分类**：新增 / 删除自定义收入、支出分类。
- **自动记账（实验性）**：监听微信 / 支付宝的支付成功通知，自动解析金额、收支方向与分类并写入账本。
- **其他**：底部导航切换「记账」与「报表」，支持一键恢复初始状态。

## 技术栈

Kotlin · AndroidX (Activity / Fragment / Lifecycle ViewModel / LiveData) · Room · DataStore Preferences · Gson · MPAndroidChart · Material Components · ViewBinding

## 架构

单模块工程，入口模块为 `app`，遵循 ViewModel + Repository 分层：

- `MainActivity`：主导航容器，通过底部导航在「记账（HomeFragment）」与「报表（ReportFragment）」间切换。
- `MainViewModel`（`AndroidViewModel`）：统一管理账本数据、首页统计、筛选状态、分类与自动记账开关，通过 LiveData 驱动界面刷新。
- `ReportViewModel`（`AndroidViewModel`）：报表页专用，负责图表（饼图/柱状图）聚合与报表摘要，与主页逻辑解耦。
- `LedgerRepository`：账本条目走 Room，预算与自定义分类走 DataStore，并负责旧版本 DataStore JSON 数据一次性迁移到 Room；筛选已下推到 Room `@Query`。
- 数据持久化：
  - **Room**：`ledger_entries` 表，金额以“分”（`Long`）存储避免浮点误差，时间以 epoch 毫秒存储。
  - **DataStore Preferences**：保存月预算与自定义分类。
  - 备份格式为 JSON（`BackupData`，version 2），含条目、资产、预算与自定义分类。

## 环境与构建

- Gradle Kotlin DSL，Gradle 9.5.1（wrapper）。
- `minSdk 24` / `targetSdk 36` / `compileSdk 36`，Java 11。
- 当前版本 `1.8.1`，包名 `com.example.personalledger`。

构建步骤：

1. 用 Android Studio 打开项目根目录，等待 Gradle 同步完成并准备好 Android SDK。
2. 连接真机或启动模拟器，运行 `app` 模块。
3. 如需构建安装包：`.\gradlew assembleRelease`。

## 目录结构

```
app/
  src/main/java/com/example/personalledger/   # 核心 Kotlin 业务与界面
  src/main/res/                               # 布局、图标、字体、动画、菜单等资源
  src/androidTest/                            # 仪器化测试
  src/test/                                   # 单元测试
  build.gradle.kts                            # app 模块构建配置
gradle/libs.versions.toml                     # 版本目录（version catalog）
Database_Table_Design.md                      # 数据表设计说明（Room 现状 + 关系型扩展设计）
tools/generate_project_summary_doc.py         # 项目总结文档生成脚本
```

## 自动记账（实验性）

首页菜单「自动记账」可开启。当微信/支付宝弹出发送“支付成功”通知时，App 会解析并自动写入账本。

- 前置：需在系统「通知使用权」中为本应用授权，并在 App 内打开开关。
- 说明：**仅个人自用 / 非上架**方可使用（涉及隐私与平台条款）；受通知内容限制，无法识别全部交易，建议核对。
- 分类：按“收款方关键词→分类”规则自动归类，未命中则归入「其他」。

## 说明

本仓库不含可运行的云端服务或后端；全部功能均为本地离线实现。

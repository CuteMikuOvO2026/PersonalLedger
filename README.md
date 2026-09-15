# PersonalLedger（轻账）

个人本地离线记账 App，基于 Kotlin + Jetpack 实现，无需账号体系与网络，数据保存在设备端。整体为**天蓝色调的清新现代 UI**，支持**浅色 / 深色 / 跟随系统**主题，并提供分类配色与可视化报表。

## 功能

- **记账**：记录收入 / 支出，支持金额、分类、备注与时间。
- **账单管理**：编辑、删除（带撤销）、按备注关键词搜索，以及按类型 / 分类 / 金额区间 / 日期区间筛选。
- **首页分页**：最近记录按页展示，**每页最多 10 条**，底部页码栏可上一页 / 下一页；筛选与分页都下推到 Room（`LIMIT/OFFSET`），首页只读取当前页数据，记录很多时也能快速打开。
- **预算**：设置本月预算，根据当月支出实时计算进度，进度条颜色随使用率变化（80% 预警、100% 超支）。
- **报表**：支出分类占比**饼图**、最近 7 天每日支出**柱状图**，并生成报表摘要；**点击柱状图的某一天，可查看当日支出明细**（分类、金额、备注、时间）。
- **饼图时间筛选**：饼图上方提供**全部 / 近 7 天 / 近一个月 / 近三个月**四个时间范围选项，默认显示全部数据；切换后饼图实时刷新为对应时间范围内的支出分布，点击饼图分类查看明细时也保持同一时间口径。
- **分类配色**：内置各分类（餐饮 / 交通 / 购物 / 娱乐 / 医疗 / 教育 / 住房 / 工资 / 奖金 / 投资 / 兼职等）分配不同柔和颜色；**新增自定义分类时可从 12 色板中自选颜色**，并持久化保存。
- **主题模式**：顶栏「外观」可切换**浅色 / 深色 / 跟随系统**，实时生效并记忆；深色模式下所有界面（含首页月度总结卡）均已适配。
- **数据导出**：导出 CSV（带 UTF-8 BOM，兼容 Excel）、导出 PDF、JSON 备份导出与导入恢复。
- **自定义分类**：新增 / 删除自定义收入、支出分类。
- **自动记账（实验性）**：监听微信 / 支付宝的支付成功通知，自动解析金额、收支方向与分类并写入账本。
- **其他**：底部导航在「记账（Home）」与「报表（Report）」间**淡入淡出**切换；支持一键恢复初始状态。

## 界面

- 整体为**天蓝色主色**的浅色清新风格，配青绿 / 琥珀 / 柔红等辅色与多色报表色板。
- **全新 App 图标**：天蓝渐变背景 + 极简白色「¥」符号。
- **页面切换**：底部导航切换使用轻盈淡入淡出（crossfade），更顺滑。

## 技术栈

Kotlin · AndroidX (Activity / Fragment / Lifecycle ViewModel / LiveData) · Room · DataStore Preferences · Gson · MPAndroidChart · Material Components · ViewBinding

## 架构

单模块工程，入口模块为 `app`，遵循 ViewModel + Repository 分层：

- `App`（`Application`）：启动时**非阻塞**地应用已保存的主题模式，避免主线程卡顿。
- `MainActivity`：主导航容器，通过底部导航在「记账（HomeFragment）」与「报表（ReportFragment）」间切换。
- `MainViewModel`（`AndroidViewModel`）：统一管理账本数据、首页统计、筛选状态、**首页分页状态**、分类、自动记账开关与主题模式，通过 LiveData 驱动界面刷新；首页统计由 Room 聚合（`SUM(CASE WHEN ...)`）得出，首页列表只读取当前页；启动时监听自定义分类并将配色注册到 `CategoryColors`。
- `ReportViewModel`（`AndroidViewModel`）：报表页专用，负责图表（饼图/柱状图）聚合与报表摘要，并暴露最近 7 天的完整日期供「点击某天查当日记录」使用；**饼图时间范围**由独立的 `pieTimeRange` 状态流驱动，与柱状图解耦。
- `LedgerStats`：报表统计的纯函数工具，含**饼图时间范围**枚举 `PieTimeRange`（全部 / 近 7 天 / 近一个月 / 近三个月）与按本地时区整日边界回溯的过滤逻辑，便于单元测试。
- `LedgerRepository`：账本条目走 Room，预算、自定义分类与主题走 DataStore，并负责旧版本 DataStore JSON 数据一次性迁移到 Room；筛选、排序与分页（`LIMIT/OFFSET`）均已下推到 Room `@Query`。
- `LedgerPaging`：首页每页条数（10 条）与页码 / 偏移量换算的纯函数，便于单元测试。
- `CategoryColors`：分类配色方案（内置分类色映射 + 自定义分类颜色注册表 + 选择色板）。
- `ThemeSettings`：主题模式（浅色 / 深色 / 跟随系统）常量与即时存储。
- 数据持久化：
  - **Room**：`ledger_entries` 表，金额以“分”（`Long`）存储避免浮点误差，时间以 epoch 毫秒存储。
  - **DataStore Preferences**：保存月预算、自定义分类（含所选颜色）与主题模式；主题模式另用 SharedPreferences 即时镜像，便于启动时同步读取。
  - 备份格式为 JSON（`BackupData`，version 2），含条目、资产、预算与自定义分类。

  
## 目录结构

```
app/
  src/main/java/com/example/personalledger/   # 核心 Kotlin 业务与界面
    MainActivity.kt / HomeFragment.kt / ReportFragment.kt      # 界面
    MainViewModel.kt / ReportViewModel.kt                       # 视图模型
    LedgerRepository.kt / DataStoreManager.kt                   # 数据仓库与设置
    CategoryItem.kt / CategoryColors.kt / CategoryAdapter.kt    # 分类模型 / 配色
    ThemeSettings.kt / App.kt                                   # 主题模式 / 启动应用
    LedgerItem.kt / LedgerEntryEntity.kt / LedgerEntryDao.kt ... # 数据模型
    LedgerStats.kt / LedgerPaging.kt / LedgerItemMappers.kt    # 统计 / 分页 / 映射
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

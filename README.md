# PersonalLedger（轻账）

个人本地离线记账 App，基于 Kotlin + Jetpack 实现，无需账号体系与网络，数据保存在设备端。整体为**天蓝色调的清新现代 UI**，支持**浅色 / 深色 / 跟随系统**主题，并提供分类配色与可视化报表。

## 功能

- **记账**：记录收入 / 支出，支持金额、分类、备注与时间。**时间可编辑**——点击「时间」先选日期、再选时刻，既能补记昨天那笔，也能修正已有记录的时间。
- **账单管理**：编辑、删除（带撤销）、按备注关键词搜索，以及按类型 / 分类 / 金额区间 / 日期区间筛选。
- **首页分页**：最近记录按页展示，**每页最多 10 条**，底部页码栏可上一页 / 下一页；筛选与分页都下推到 Room（`LIMIT/OFFSET`），首页只读取当前页数据，记录很多时也能快速打开。
- **首页洞察卡**：一张卡给出**本月最高支出分类 / 日均支出 / 与上月环比**；上月没有支出时不编造百分比，显示「—」。
- **预算**：可设置**多条预算规则**——不限分类的**总预算**或**按分类**的限额，周期可选**每月 / 每周**；首页展示月度总预算进度（进度条颜色随使用率变化，80% 预警、100% 超支），存在超支的分类预算时额外给出提示，点击进入预算管理可查看每条规则的「当期已花 / 限额 / 百分比 / 状态」。
- **预算提醒**：可选开启，定时检查预算使用情况，达到 80% 或超支时发送通知；同一周期内不重复提醒。
- **报表**：支出分类占比**饼图**、最近 7 天每日支出**柱状图**，并生成报表摘要；**点击柱状图的某一天，可查看当日支出明细**（分类、金额、备注、时间）。
- **饼图时间筛选**：饼图上方提供**全部 / 近 7 天 / 近一个月 / 近三个月**四个时间范围选项，默认显示全部数据；切换后饼图实时刷新为对应时间范围内的支出分布，点击饼图分类查看明细时也保持同一时间口径。
- **分类配色**：内置各分类（餐饮 / 交通 / 购物 / 娱乐 / 医疗 / 教育 / 住房 / 工资 / 奖金 / 投资 / 兼职等）分配不同柔和颜色；**新增自定义分类时可从 12 色板中自选颜色**，并持久化保存。
- **主题模式**：顶栏「外观」可切换**浅色 / 深色 / 跟随系统**，实时生效并记忆；深色模式下所有界面（含首页月度总结卡）均已适配。同处可开启 **Material You 动态取色**（跟随壁纸配色，需 Android 12+，默认关闭以保留本应用的固定天蓝配色）。
- **无障碍**：**尊重系统「字体大小」设置**（缩放上限 1.3×，原因见下文）。
- **数据导出**：导出 CSV（带 UTF-8 BOM，兼容 Excel）、导出 PDF、JSON 备份导出与导入恢复。
- **CSV 导入**：可导入本应用导出的 CSV，也可导入其他记账 App 的 CSV——解析后提供**列映射确认**（时间 / 类型 / 分类 / 金额 / 备注），导入为**追加**而非覆盖，无法解析的行会跳过并告知数量。
- **自动本地备份**：可选开启，每天自动把完整备份写入应用专属目录，只保留最近 5 份，并可从列表中选择某一份恢复；全程不涉及网络。
- **自定义分类**：新增 / 删除自定义收入、支出分类。
- **自动记账（实验性）**：监听微信 / 支付宝的支付成功通知，自动解析金额、收支方向与分类并写入账本。
- **其他**：底部导航在「记账（Home）」与「报表（Report）」间**淡入淡出**切换；支持一键恢复初始状态。

## 界面

- 整体为**天蓝色主色**的浅色清新风格，配青绿 / 琥珀 / 柔红等辅色与多色报表色板。
- **全新 App 图标**：天蓝渐变背景 + 极简白色「¥」符号。
- **页面切换**：底部导航切换使用轻盈淡入淡出（crossfade），更顺滑。

## 技术栈

Kotlin · AndroidX (Activity / Fragment / Lifecycle ViewModel / LiveData) · Room · DataStore Preferences · **WorkManager** · Gson · MPAndroidChart · Material Components · ViewBinding

> 颜色统一通过**主题属性**（`?attr/colorPrimary`、`?attr/colorOnSurface` …）读取，见 `ThemeColors`。
> 语义色（收入绿 / 支出红 / 超支琥珀）与分类配色板**刻意保持固定**——它们承载含义，不应随壁纸变化。
> 同理，分类选中态的底色（`surface_chip_selected`）也保持固定：它是「中等蓝底 + 深色文字」的对比度取舍，
> 迁移到 `?attr/colorPrimary` 反而会让深色模式下的选中态文字更难读。

## 架构

单模块工程，入口模块为 `app`，遵循 ViewModel + Repository 分层：

- `App`（`Application`）：启动时**非阻塞**地应用已保存的主题模式，避免主线程卡顿；按开关状态注册 Material You 动态取色（用 `precondition` 表达开关，切换后只需重建 Activity 即可生效），并同步两个定时任务的排期。
- `MainActivity`：主导航容器，通过底部导航在「记账（HomeFragment）」与「报表（ReportFragment）」间切换；`attachBaseContext` 只锁定中文文案，**字体缩放交给系统**（上限 1.3×，原因见 `MainActivity.MAX_FONT_SCALE` 处的注释）。
- `MainViewModel`（`AndroidViewModel`）：统一管理账本数据、首页统计、**预算规则与执行情况**、**首页洞察**、筛选状态、**首页分页状态**、分类、自动记账开关与主题模式，通过 LiveData 驱动界面刷新；首页统计由 Room 聚合得出（收入、支出**各发一条按方向 + 本月区间的范围查询**，时间条件放在 `WHERE` 里以便走索引，再由 `LedgerStats.buildHomeTotals` 组装），首页列表只读取当前页；启动时监听自定义分类并将配色注册到 `CategoryColors`。
- `ReportViewModel`（`AndroidViewModel`）：报表页专用，图表与摘要**全部由 Room 聚合得出**（`GROUP BY categoryName` / 7 个 `SUM(CASE WHEN ...)` 分桶 / 全表收支合计），不再读取整表；点击某天或某分类时再按需查询明细。**饼图时间范围**由独立的 `pieTimeRange` 状态流驱动，与柱状图解耦；「今天」锚点（`dayAnchor`）在 `onResume` 刷新，保证跨零点后两个图表的窗口一起前移。
- `LedgerStats`：统计与报表的纯函数工具，分两类职责——**时间窗口**（`PieTimeRange` 枚举、`currentStatsRanges`、`todayRange` / `monthRange` / `weekRange` / `lastMonthRange`、`weeklyDayRanges`、`rangeStartMillisOrNull`、`startOfDay`、`periodKey`，统一按本地时区对齐自然边界）与**聚合结果换算**（`buildHomeTotals` / `buildHomeStats` / `buildPieEntries` / `buildWeeklyBarEntries` / `buildReportSummary`）。另有若干 `getXxx(list)` 形式的内存版实现，仅用于与数据库聚合结果**对拍**（见 `LedgerStatsAggregationTest`），保证两条路径数字一致。
- `BudgetStats` / `BudgetRule` / `BudgetEntity` / `BudgetDao`：预算规则与执行情况的纯计算。预算存的是一条**长期生效的规则**（`(周期, 分类)` 复合主键），当期进度用当期支出实时算，不为每个周期建行。状态阈值（80% 预警 / 100% 超支）只存在于 `BudgetStats` 一处，进度条配色与提醒共用。
- `HomeInsights`：首页洞察的纯计算（最高支出分类 / 日均支出 / 环比），日均按「本月已过天数」摊，上月无支出时环比返回 `null` 而不是编造百分比。
- `LedgerDateTime`：日期 / 时刻选择器与本地时间戳的换算。**关键坑**：`MaterialDatePicker` 用 **UTC 零点**表示日期，直接当本地时间戳用会整体偏移一天（东八区表现为「选了 3 月 1 日却存成 2 月 28 日」）。
- `CsvLedgerParser`：CSV 的解析与转换纯函数——`parse` 只管切表（支持引号包裹、字段内逗号换行、`""` 转义、CRLF、BOM），`suggestMapping` 按表头猜列，两者分离才能同时接住自家导出与其他 App 的格式。
- `LedgerRepository`：账本条目与预算走 Room，自定义分类 / 自动记账开关 / 主题模式走 DataStore，并负责跨存储的一次性搬迁（DataStore JSON 历史 → Room；旧月度总预算 → `budgets` 表）；筛选、排序与分页（`LIMIT/OFFSET`）、首页统计、报表聚合与下钻查询均已下推到 Room `@Query`。**刻意不提供「整表响应式读取」入口**：任何界面都不需要把 `ledger_entries` 整表读进内存，只有备份 / CSV 导出用一次性的挂起函数取全量。
- `BudgetAlertWorker` / `AutoBackupWorker`：两个 WorkManager 定时任务（预算预警检查、每日自动备份），均用 `KEEP` 策略入队，因此 `App.onCreate` 可以无条件同步而不重置计时；去重与开关状态存在 SharedPreferences（后台需要同步读取）。
- `LedgerPaging`：首页每页条数（10 条）与页码 / 偏移量换算的纯函数，便于单元测试。
- `CategoryColors`：分类配色方案（内置分类色映射 + 自定义分类颜色注册表 + 选择色板）。
- `ThemeColors`：主题属性色解析，让界面颜色跟随主题（进而支持动态取色）。
- `ThemeSettings`：主题模式（浅色 / 深色 / 跟随系统）与动态取色开关的即时存储。
- 数据持久化：
  - **Room**：`ledger_entries` 表（金额以“分”`Long` 存储避免浮点误差，时间以 epoch 毫秒存储）+ `budgets` 表。当前 schema 版本 **v3**；`ledger_entries` 建了 4 个索引（`timeMillis`、`isExpense+timeMillis`、`categoryName+timeMillis`、`isExpense+categoryName+timeMillis`）支撑分页排序、日期区间筛选与报表分组。
  - **升级约定**：任何改动实体（字段 / 索引 / 表）的提交都必须**同时**升 `AppDatabase` 版本号、补一条 `Migration`、并把 KSP 导出的 `app/schemas/<版本>.json` 一并提交；`AppDatabaseMigrationTest` 会用 `MigrationTestHelper` 逐段校验迁移结果与导出的 schema 完全一致。
  - **DataStore Preferences**：保存自定义分类（含所选颜色）、自动记账开关与主题模式；主题模式另用 SharedPreferences 即时镜像，便于启动时同步读取。
  - 备份格式为 JSON（`BackupData`，version 3），含条目、**预算规则列表**与自定义分类；读取 v2 及更早的备份时会用旧的单个 `budget` 值补一条月度总预算，保证老备份不丢预算。备份字段刻意声明为**可空**——Gson 反序列化 Kotlin data class 时不执行构造器与默认值，缺失字段会是 `null` 而非默认值。

  
## 目录结构

```
app/
  src/main/java/com/example/personalledger/   # 核心 Kotlin 业务与界面
    MainActivity.kt / HomeFragment.kt / ReportFragment.kt      # 界面
    MainViewModel.kt / ReportViewModel.kt                       # 视图模型
    LedgerRepository.kt / DataStoreManager.kt                   # 数据仓库与设置
    BudgetEntity.kt / BudgetRule.kt / BudgetDao.kt / BudgetStats.kt  # 预算规则与进度
    BudgetAlertSettings.kt / BudgetAlertNotifier.kt / BudgetAlertWorker.kt  # 预算预警
    AutoBackupWorker.kt                                         # 自动本地备份
    HomeInsight.kt / CsvLedgerParser.kt / LedgerDateTime.kt     # 洞察 / CSV 解析 / 时间换算
    CategoryItem.kt / CategoryColors.kt / CategoryAdapter.kt    # 分类模型 / 配色
    ThemeSettings.kt / ThemeColors.kt / App.kt                  # 主题 / 主题色解析 / 启动应用
    LedgerItem.kt / LedgerEntryEntity.kt / LedgerEntryDao.kt ... # 数据模型
    AppDatabase.kt                                              # Room 数据库与版本迁移
    LedgerStats.kt / LedgerPaging.kt / LedgerItemMappers.kt    # 统计 / 分页 / 映射
  src/main/res/                               # 布局、图标、字体、动画、菜单等资源
  schemas/                                    # KSP 导出的 Room schema（含迁移测试用历史版本）
  src/androidTest/                            # 仪器化测试（Room 迁移校验）
  src/test/                                   # 单元测试（统计 / 预算 / 洞察 / CSV / 时间换算）
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

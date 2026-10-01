# PersonalLedger（轻账）

个人本地离线记账 App。基于 Kotlin + Jetpack 实现，**没有账号体系、没有网络请求、没有后端**，全部数据保存在设备本地。

界面为天蓝色调的清新现代风格，支持浅色 / 深色 / 跟随系统三种主题，并提供分类配色与可视化报表。

---

## 功能

### 记账与账单

- **记账**：记录收入 / 支出，支持金额、分类、备注与时间。**时间可编辑** —— 点击「时间」先选日期、再选时刻，既能补记昨天那笔，也能修正已有记录的时间。
- **账单管理**：编辑、删除（带撤销）、按备注关键词搜索。
- **筛选**：按类型 / 分类 / 金额区间 / 日期区间组合筛选。
- **首页分页**：最近记录按页展示，**每页最多 10 条**，底部页码栏可上一页 / 下一页。筛选与分页都下推到 Room（`LIMIT` / `OFFSET`），首页只读取当前页数据，记录很多时也能快速打开。

### 统计与报表

- **首页洞察卡**：一张卡给出**本月最高支出分类 / 日均支出 / 与上月环比**。上月没有支出时**不编造百分比**，显示「—」。
- **报表**：支出分类占比**饼图** + 最近 7 天每日支出**柱状图**，并生成报表摘要。
- **报表下钻**：点击柱状图的某一天，或点击饼图的某个分类，查看当日 / 该分类的支出明细（分类、金额、备注、时间）。
- **饼图时间筛选**：饼图上方提供**全部 / 近 7 天 / 近一个月 / 近三个月**四个时间范围，默认显示全部；切换后饼图实时刷新为对应范围内的支出分布，下钻明细也保持同一时间口径。
- 图表与摘要**全部由 Room 聚合得出**（`GROUP BY` / `SUM(CASE WHEN ...)` 分桶 / 收支合计），只有点击下钻时才按需查询明细。

### 预算

- **多条预算规则**：可设置不限分类的**总预算**或**按分类**的限额，周期可选**每月 / 每周**。
- **首页进度**：展示月度总预算进度，进度条颜色随使用率变化（**80% 预警、100% 超支**）；存在超支的分类预算时额外给出提示，点击进入预算管理查看每条规则的「当期已花 / 限额 / 百分比 / 状态」。
- **预算提醒**：可选开启，定时检查预算使用情况，达到 80% 或超支时发送通知；**同一周期内不重复提醒**。

### 数据导入导出与备份

- **导出 CSV**：带 UTF-8 BOM，兼容 Excel。
- **导出 PDF**：把报表导出为 PDF 文件。
- **JSON 备份导出与导入恢复**。
- **CSV 导入**：可导入本应用导出的 CSV，也可导入其他记账 App 的 CSV。解析后提供**列映射确认**（时间 / 类型 / 分类 / 金额 / 备注），导入为**追加**而非覆盖，无法解析的行会跳过并告知数量。
- **自动本地备份**：可选开启，每天自动把完整备份写入应用专属目录，**只保留最近 5 份**，并可从列表中选择某一份恢复。全程不涉及网络。

### 个性化

- **分类配色**：内置各分类（餐饮 / 交通 / 购物 / 娱乐 / 医疗 / 教育 / 住房 / 工资 / 奖金 / 投资 / 兼职等）分配不同柔和颜色；**新增自定义分类时可从 12 色板中自选颜色**，并持久化保存。
- **自定义分类**：新增 / 删除自定义收入、支出分类。
- **主题模式**：顶栏「外观」可切换**浅色 / 深色 / 跟随系统**，实时生效并记忆。
- **Material You 动态取色**：同处可开启（跟随壁纸配色，需 Android 12+），**默认关闭**以保留本应用固定的天蓝配色。
- **字号**：应用内字号统一按 1.0× 固定，**不跟随系统「字体大小」缩放**（原因见 `MainActivity.attachBaseContext` 的注释）。
- **其他**：底部导航在「记账」与「报表」间**淡入淡出**切换；支持一键恢复初始状态。

### 自动记账（实验性）

监听微信 / 支付宝的支付成功通知，自动解析金额、收支方向与分类并写入账本。详见下方[自动记账](#自动记账实验性)一节。

---

## 界面

- 整体为**天蓝色主色**的浅色清新风格，配青绿 / 琥珀 / 柔红等辅色与多色报表色板。
- **App 图标**：天蓝渐变背景 + 极简白色「¥」符号。
- **页面切换**：底部导航使用轻盈的淡入淡出（crossfade）。
- 深色模式下所有界面（含首页月度总结卡）均已适配。

> 颜色统一通过**主题属性**（`?attr/colorPrimary`、`?attr/colorOnSurface` …）读取，见 `ThemeColors`。
> 语义色（收入绿 / 支出红 / 超支琥珀）与分类配色板**刻意保持固定** —— 它们承载含义，不应随壁纸变化。
> 同理，分类选中态的底色（`surface_chip_selected`）也保持固定：它是「中等蓝底 + 深色文字」的对比度取舍，
> 迁移到 `?attr/colorPrimary` 反而会让深色模式下的选中态文字更难读。

---

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Android Gradle Plugin | 9.1.0 | 构建 |
| Kotlin / KSP | 2.2.10 / 2.2.10-2.0.2 | 语言与注解处理 |
| Gradle | 9.5.1（wrapper） | 构建工具 |
| JDK | 21（Gradle daemon toolchain） | 构建环境 |
| Java 兼容级别 | 11 | `sourceCompatibility` / `targetCompatibility` |
| Room | 2.7.0 | 本地数据库（含 `room-ktx`、`room-compiler`、`room-testing`） |
| DataStore Preferences | 1.1.1 | 设置项持久化 |
| WorkManager | 2.10.0 | 定时任务 |
| Lifecycle | 2.8.0 | ViewModel / LiveData |
| AppCompat / Material | 1.6.1 / 1.10.0 | 界面基础 |
| Activity / Fragment | 1.13.0 / 1.6.2 | 界面容器 |
| ConstraintLayout | 2.1.4 | 布局 |
| Gson | 2.10.1 | JSON 序列化 |
| MPAndroidChart | v3.1.0 | 饼图 / 柱状图 |
| ViewBinding | 已启用 | 视图绑定 |

---

## 架构

单模块工程，入口模块为 `app`，遵循 ViewModel + Repository 分层。

### 界面层

- **`App`**（`Application`）：启动时**非阻塞**地应用已保存的主题模式，避免主线程卡顿；按开关状态注册 Material You 动态取色（用 `precondition` 表达开关，切换后只需重建 Activity 即可生效），并同步两个定时任务的排期。
- **`MainActivity`**：主导航容器，通过底部导航在「记账（`HomeFragment`）」与「报表（`ReportFragment`）」间切换；`attachBaseContext` 锁定中文文案并把 `fontScale` 固定为 **1.0×**（原因见该方法内的注释）。
- **`HomeFragment`** / **`ReportFragment`**：两个页面。首页包含洞察卡、预算进度、筛选与分页列表；报表页包含饼图、柱状图与下钻明细。外观（主题 / 动态取色）弹窗在首页顶栏菜单中。
- **`AddEntryBottomSheetDialogFragment`**：记账 / 编辑账目的底部表单，含金额、类型、分类、备注与日期时刻选择。
- **`LedgerAdapter`** / **`CategoryAdapter`**：账目列表与分类列表的适配器。

### 视图模型层

- **`MainViewModel`**（`AndroidViewModel`）：统一管理账本数据、首页统计、**预算规则与执行情况**、**首页洞察**、筛选状态、**首页分页状态**、分类、自动记账开关与主题模式，通过 LiveData 驱动界面刷新。
  **主题模式是 `StateFlow`**：它没有常驻观察者，而「外观」弹窗要随时读一次当前值，`Flow.asLiveData()` 在无人订阅时 `.value` 恒为 null，正是「始终显示跟随系统」的成因。
- **`ReportViewModel`**（`AndroidViewModel`）：报表页专用，图表与摘要全部由 Room 聚合得出；点击某天或某分类时再按需查询明细。**饼图时间范围**由独立的 `pieTimeRange` 状态流驱动，与柱状图解耦；「今天」锚点（`dayAnchor`）在 `onResume` 刷新，保证跨零点后两个图表的窗口一起前移。

### 数据层

- **`LedgerRepository`**：账本条目与预算走 Room，自定义分类 / 自动记账开关走 DataStore（**主题模式不经过这里**，见 `ThemeSettings`），并负责跨存储的一次性搬迁（DataStore JSON 历史 → Room；旧月度总预算 → `budgets` 表）。筛选、排序与分页（`LIMIT` / `OFFSET`）、首页统计、报表聚合与下钻查询均已下推到 Room `@Query`。
  **刻意不提供「整表响应式读取」入口**：任何界面都不需要把 `ledger_entries` 整表读进内存，只有备份 / CSV 导出用一次性的挂起函数取全量。
- **`AppDatabase`**：Room 数据库与版本迁移（当前 schema v3）。
- **`LedgerEntryDao`** / **`BudgetDao`**：查询接口。
- **`DataStoreManager`**：DataStore Preferences 的键定义与读写。
- **`LedgerItemJsonAdapter`**：备份 JSON 中账目字段的 Gson 适配。

### 纯函数与工具层

这些类**不依赖 Android 框架或只依赖最小接口**，把逻辑与界面、数据库隔开，便于单独推演与排查：

- **`LedgerStats`**：统计与报表的纯函数工具，分两类职责 —— **时间窗口**（`PieTimeRange` 枚举、`currentStatsRanges`、`todayRange` / `monthRange` / `weekRange` / `lastMonthRange`、`weeklyDayRanges`、`rangeStartMillisOrNull`、`startOfDay`、`periodKey`，统一按本地时区对齐自然边界）与**聚合结果换算**（`buildHomeTotals` / `buildHomeStats` / `buildPieEntries` / `buildWeeklyBarEntries` / `buildReportSummary`），把数据库聚合出的「分」金额换算成界面数据。
- **`BudgetStats`**：预算执行情况的纯计算。**状态阈值（80% 预警 / 100% 超支）只存在于这一处**，进度条配色与提醒共用。
- **`HomeInsight`**：首页洞察的纯计算（最高支出分类 / 日均支出 / 环比），日均按「本月已过天数」摊，上月无支出时环比返回 `null` 而不是编造百分比。
- **`LedgerDateTime`**：日期 / 时刻选择器与本地时间戳的换算。**关键坑**：`MaterialDatePicker` 用 **UTC 零点**表示日期，直接当本地时间戳用会整体偏移一天（东八区表现为「选了 3 月 1 日却存成 2 月 28 日」）。
- **`CsvLedgerParser`**：CSV 的解析与转换纯函数 —— `parse` 只管切表（支持引号包裹、字段内逗号换行、`""` 转义、CRLF、BOM），`suggestMapping` 按表头猜列。两者分离才能同时接住自家导出与其他 App 的格式。
- **`LedgerPaging`**：首页每页条数（10 条）与页码 / 偏移量换算的纯函数。
- **`CategoryColors`**：分类配色方案（内置分类色映射 + 自定义分类颜色注册表 + 选择色板）。
- **`ThemeColors`**：主题属性色解析，让界面颜色跟随主题（进而支持动态取色）。
- **`ThemeSettings`**：主题模式（浅色 / 深色 / 跟随系统）与动态取色开关的**唯一存储**（SharedPreferences，同步读写）。主题模式必须在 `App.onCreate` 里**同步**读取才能避免启动闪白，异步的 DataStore 做不到，因此不再另存一份 —— 两份迟早不一致，界面回显与实际生效的值就会打架。
- **`LedgerItemMappers`** / **`LedgerItem`** / **`CategoryItem`**：数据模型与映射。

### 后台任务

- **`BudgetAlertWorker`** / **`AutoBackupWorker`**：两个 WorkManager 定时任务（预算预警每 6 小时检查一次、每日自动备份一次），均用 `KEEP` 策略入队，因此 `App.onCreate` 可以无条件同步而不重置计时。
- 去重与开关状态存在 SharedPreferences（后台需要同步读取）。
- 自动备份文件写在**应用专属外部目录**（`Android/data/<包名>/files/backups`），不需要存储权限、卸载即清理，外部存储不可用时退回内部目录。

### 数据持久化

- **Room**：`ledger_entries` 表 + `budgets` 表。当前 schema 版本 **v3**。

  `ledger_entries` 字段：`id`(PK)、`amountCents`(Long)、`note`、`timeMillis`(Long)、`isExpense`(Boolean)、`categoryName`、`categoryIconRes`。
  金额以「分」存储避免浮点误差，时间以 epoch 毫秒存储以便索引与聚合。建有 4 个索引：

  | 索引 | 支撑的查询 |
  |---|---|
  | `timeMillis` | 首页 `ORDER BY timeMillis DESC` 翻页、日期区间筛选 |
  | `isExpense + timeMillis` | 今日 / 本月收支聚合、报表近 7 天分桶 |
  | `categoryName + timeMillis` | 分类筛选翻页、筛选弹窗的分类去重 |
  | `isExpense + categoryName + timeMillis` | 饼图（`isExpense=1` + 时间下界 + `GROUP BY categoryName`），可覆盖扫描 |

  `budgets` 表：`(periodType, categoryName)` 复合主键 + `limitCents`。预算存的是一条**长期生效的规则**，当期进度用当期支出实时算，不为每个周期建行。

- **升级约定（重要）**：任何改动实体（字段 / 索引 / 表）的提交都必须**同时**完成三件事 —— 升 `AppDatabase` 版本号、补一条 `Migration`、把 KSP 导出的 `app/schemas/<版本>.json` 一并提交。缺少迁移会让老用户升级后启动即崩（`IllegalStateException: A migration from X to Y was required but not found`），导出的 schema 则用于代码评审时比对结构变化。

  现有迁移：`MIGRATION_1_2`（补 4 个索引）、`MIGRATION_2_3`（新增 `budgets` 表）。

- **DataStore Preferences**：保存自定义分类（含所选颜色）与自动记账开关。
- **SharedPreferences**：`ThemeSettings`（主题模式 + 动态取色）、`BudgetAlertSettings`（预警开关 + 去重状态）、`AutoBackupSettings`（备份开关）。这三处都需要后台或启动时**同步**读取，异步的 DataStore 做不到。
- **备份格式**：JSON（`BackupData`，version 3），含条目、**预算规则列表**与自定义分类。读取 v2 及更早的备份时会用旧的单个 `budget` 值补一条月度总预算，保证老备份不丢预算。备份字段刻意声明为**可空** —— Gson 反序列化 Kotlin data class 时不执行构造器与默认值，缺失字段会是 `null` 而非默认值。

---

## 目录结构

```
PersonalLedger/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                # 组件与权限声明
│   │   ├── java/com/example/personalledger/   # 37 个 Kotlin 源文件
│   │   │   ├── App.kt / MainActivity.kt                        # 启动与导航容器
│   │   │   ├── HomeFragment.kt / ReportFragment.kt             # 两个主页面
│   │   │   ├── AddEntryBottomSheetDialogFragment.kt            # 记账 / 编辑表单
│   │   │   ├── MainViewModel.kt / ReportViewModel.kt           # 视图模型
│   │   │   ├── LedgerRepository.kt / DataStoreManager.kt       # 数据仓库与设置
│   │   │   ├── AppDatabase.kt / LedgerEntryEntity.kt           # Room 数据库与实体
│   │   │   ├── LedgerEntryDao.kt / BudgetDao.kt                # DAO
│   │   │   ├── BudgetEntity.kt / BudgetRule.kt / BudgetStats.kt    # 预算规则与进度
│   │   │   ├── BudgetAlertSettings.kt / BudgetAlertNotifier.kt / BudgetAlertWorker.kt  # 预算预警
│   │   │   ├── AutoBackupWorker.kt                             # 自动本地备份
│   │   │   ├── LedgerStats.kt / HomeInsight.kt / LedgerPaging.kt   # 统计 / 洞察 / 分页（纯函数）
│   │   │   ├── LedgerDateTime.kt / CsvLedgerParser.kt          # 时间换算 / CSV 解析（纯函数）
│   │   │   ├── LedgerNotificationListenerService.kt            # 通知监听服务
│   │   │   ├── NotificationPaymentParser.kt / AutoBookkeepingRules.kt  # 自动记账解析与规则
│   │   │   ├── LedgerItem.kt / LedgerItemMappers.kt / LedgerItemJsonAdapter.kt  # 数据模型与映射
│   │   │   ├── CategoryItem.kt / CategoryColors.kt / CategoryAdapter.kt         # 分类模型与配色
│   │   │   └── ThemeSettings.kt / ThemeColors.kt / LedgerAdapter.kt             # 主题与列表适配
│   │   └── res/                                # 62 个资源文件
│   │       ├── layout/                         # 6 个布局
│   │       ├── drawable/ + drawable-night/     # 图标与背景（含深色变体）
│   │       ├── values/ + values-night/         # 颜色、主题、样式、文案
│   │       ├── font/                           # 内嵌 Noto Sans SC 字体
│   │       ├── menu/ + anim/ + color/ + xml/   # 菜单、动画、着色、备份规则
│   │       └── mipmap-*/                       # 各密度应用图标
│   ├── schemas/                                # KSP 导出的 Room schema（1/2/3.json，供对照与评审）
│   ├── build.gradle.kts                        # app 模块构建配置
│   └── proguard-rules.pro
├── gradle/
│   ├── libs.versions.toml                      # 版本目录（version catalog）
│   ├── gradle-daemon-jvm.properties            # daemon JDK toolchain（21）
│   └── wrapper/                                # Gradle wrapper
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat
└── README.md
```
---
## 说明

- 本仓库**不含可运行的云端服务或后端**，全部功能均为本地离线实现。
- 应用申请的唯一权限是 `POST_NOTIFICATIONS`（预算预警通知，Android 13+ 属运行时权限）。自动记账依赖系统的「通知使用权」，不是普通权限。
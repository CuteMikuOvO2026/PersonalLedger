# PersonalLedger（轻账）

个人本地离线记账 App。基于 Kotlin + Jetpack 实现，**没有账号体系、没有后端**，全部数据保存在设备本地。

> 唯一的网络用途是**可选的 AI 助手**（调用 DeepSeek 把自然语言解析成账目）。不填 API Key 时
> 应用不会发出任何网络请求，账本数据本身也从不离开设备 —— 具体见下方 [AI 助手](#ai-助手可选) 一节。

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
- **其他**：底部导航在「记账」「报表」「助手」间**淡入淡出**切换；支持一键恢复初始状态。

### 自动记账（实验性）

监听微信 / 支付宝的支付成功通知，自动解析金额、收支方向与分类并写入账本。详见下方[自动记账](#自动记账实验性)一节。

### AI 助手（可选）

在「助手」页用一句话记账：说「我今天买了咖啡 花了15」，助手解析出金额、分类、备注与时间，
并以一张**待确认卡片**回给你；点「确认记账」才真正写入账本。

这是本应用**唯一**会联网的功能，且完全可选 —— 不填 API Key 时助手页不会发出任何请求。

**怎么用**

1. 点助手页右上角的「AI 设置」，填入 DeepSeek 的 API Key（在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请），选模型后保存。
   **没填 Key 之前，输入框与发送按钮是禁用的**，输入框会显示「请先在右上角填入 API Key」，空状态里也给出一条可点的提示 —— 直接从源头堵住「打完字才发现没配置」。
2. 在输入框里用自然语言描述消费，例如「昨天打车 23」「早餐 8 块，咖啡 15」。
3. 助手回一张待确认卡片，逐笔列出**分类、备注、解析出的时间**与带符号的金额。核对无误后点「确认记账」；不对就点「忽略」。

**几个刻意设计**

- **先确认再写入，助手不直接改账本。** 模型会编造内容，而账本里一笔错账用户很难事后发现 —— 这与通知自动记账「金额算错会静默写进账本」是同一类风险，所以这里必须留一道人工确认。
- **一句话里的多笔消费一次全部解析**，共用一张确认卡片。
- **卡片上一定要显示时间**：模型把「昨天」算错时，用户在这一行就能当场看出来。
- **分类名只认清单内的**：模型一旦返回清单外的分类名会被收敛成「其他」。账本只存分类名、没有外键，凭空多出的分类会渲染成兜底色和兜底图标，用户既选不到也删不掉。
- **金额、方向、时间任一不合格就丢掉那一笔**，绝不猜一个值补齐。
- **失败也写进对话**：401 / 402 / 429 / 断网各自给出可操作的中文说明（去改 Key / 去充值 / 稍后再试 / 检查网络），而不是笼统的「请求失败」。
- **「恢复初始状态」会一并清空对话**，备份文件里**不含**对话记录。

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

> AI 助手的 HTTP 请求由 JDK 自带的 `HttpURLConnection` 发出，**未引入 OkHttp / Retrofit** ——
> 只有一个「POST 一个 JSON、拿回一个 JSON」的非流式请求，不值得为它增加一套依赖。

---

## 架构

单模块工程，入口模块为 `app`，遵循 ViewModel + Repository 分层。

### 界面层

- **`App`**（`Application`）：启动时**非阻塞**地应用已保存的主题模式，避免主线程卡顿；按开关状态注册 Material You 动态取色（用 `precondition` 表达开关，切换后只需重建 Activity 即可生效），并同步两个定时任务的排期。
- **`MainActivity`**：主导航容器，通过底部导航在「记账（`HomeFragment`）」「报表（`ReportFragment`）」「助手（`AssistantFragment`）」间切换；`attachBaseContext` 锁定中文文案并把 `fontScale` 固定为 **1.0×**（原因见该方法内的注释）。
- **`HomeFragment`** / **`ReportFragment`**：两个页面。首页包含洞察卡、预算进度、筛选与分页列表；报表页包含饼图、柱状图与下钻明细。外观（主题 / 动态取色）弹窗在首页顶栏菜单中。
- **`AssistantFragment`**：AI 助手页（对话列表 + 底部输入条 + 待确认卡片）。**ViewModel 必须是 Activity 作用域的** —— 切页会把 Fragment 替换成新实例，Fragment 作用域的 ViewModel 会连同 `viewModelScope` 一起被清掉，表现为「发完消息切走再切回来，请求已经死了」。
- **`AddEntryBottomSheetDialogFragment`**：记账 / 编辑账目的底部表单，含金额、类型、分类、备注与日期时刻选择。
- **`LedgerAdapter`** / **`CategoryAdapter`** / **`ChatAdapter`**：账目列表、分类列表与对话列表的适配器。`ChatAdapter` 用**一个布局 + 一个 viewType**（两个气泡容器互斥显示），确认卡片里的账目行程序化生成，不嵌套 RecyclerView。

### 视图模型层

- **`MainViewModel`**（`AndroidViewModel`）：统一管理账本数据、首页统计、**预算规则与执行情况**、**首页洞察**、筛选状态、**首页分页状态**、分类、自动记账开关与主题模式，通过 LiveData 驱动界面刷新。
  **主题模式是 `StateFlow`**：它没有常驻观察者，而「外观」弹窗要随时读一次当前值，`Flow.asLiveData()` 在无人订阅时 `.value` 恒为 null，正是「始终显示跟随系统」的成因。
- **`ReportViewModel`**（`AndroidViewModel`）：报表页专用，图表与摘要全部由 Room 聚合得出；点击某天或某分类时再按需查询明细。**饼图时间范围**由独立的 `pieTimeRange` 状态流驱动，与柱状图解耦；「今天」锚点（`dayAnchor`）在 `onResume` 刷新，保证跨零点后两个图表的窗口一起前移。
- **`AssistantViewModel`**（`AndroidViewModel`）：助手页专用，与 `MainViewModel` 完全解耦（各自持有 repository），共享的只有写进同一张表的 `LedgerRepository` —— Room 的 Flow 会自动让首页与报表刷新，不需要任何跨 ViewModel 通知。
  **助手不直接写账本**：解析结果先落成一条带草稿的消息，等用户确认才写库（原因见 `AiBookkeepingParser`）。错误也写进对话而不仅弹 Toast，因为「刚才那句为什么没记账」需要在对话里留痕。

### 数据层

- **`LedgerRepository`**：账本条目与预算走 Room，自定义分类 / 自动记账开关走 DataStore（**主题模式不经过这里**，见 `ThemeSettings`），并负责跨存储的一次性搬迁（DataStore JSON 历史 → Room；旧月度总预算 → `budgets` 表）。筛选、排序与分页（`LIMIT` / `OFFSET`）、首页统计、报表聚合与下钻查询均已下推到 Room `@Query`。
  **刻意不提供「整表响应式读取」入口**：任何界面都不需要把 `ledger_entries` 整表读进内存，只有备份 / CSV 导出用一次性的挂起函数取全量。
- **`AppDatabase`**：Room 数据库与版本迁移（当前 schema v4）。
- **`LedgerEntryDao`** / **`BudgetDao`** / **`ChatMessageDao`**：查询接口。
- **`DataStoreManager`**：DataStore Preferences 的键定义与读写。
- **`LedgerItemJsonAdapter`**：备份 JSON 中账目字段的 Gson 适配。
- **`DeepSeekClient`**：**全项目唯一的联网代码**。`HttpURLConnection` 发非流式 `POST /chat/completions`，异常一律收敛成 `AiError`。两个必须踩准的点：`thinking` 要**显式关掉**（DeepSeek 的思考模式默认开启，不关则每次记账都走一遍推理，又慢又贵且 `temperature` 失效）；`frequency_penalty` / `presence_penalty` 官方已标废弃，**不要发**。

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
- **`AiBookkeepingParser`**：模型返回内容的解析与校验纯函数，与 `NotificationPaymentParser` 同样的取舍 —— **不碰 `R`**，因此可以用一段真实模型输出在 JVM 上复现。校验策略是「宁可少记，不可错记」：金额 ≤ 0 或超出上限、分类名不在清单内、时间无法解析，任一不合格就**丢掉那一笔或收敛到兜底值**，绝不猜。`Locale.ROOT` 是刻意的：某些默认地区（如泰语区佛历）会把 `yyyy` 解析偏移 543 年。
- **`AiPrompt`**：系统提示词的构造纯函数。三处硬性约束：必须出现字面量 `json` 并给出完整示例（DeepSeek JSON Output 的要求，否则模型可能一直吐空白直到耗尽 token）；必须注入当前日期锚定「今天 / 昨天 / 上周五」；必须逐个列出可用分类名（账本只存分类名、无外键，模型凭空造的会被收敛成「其他」）。
- **`DefaultCategories`**：内置分类清单的**唯一来源**，记账表单与 AI 助手共用（两边各留一份迟早漂移）。
- **`AiSettings`**：API Key 与模型的持久化。**用独立的 SharedPreferences 文件而不是 DataStore** —— DataStore 只有 `settings` 一个文件，里面还存着自定义分类，而 Key 必须排除在备份之外；按文件排除会连带丢掉用户分类。

### 后台任务

- **`BudgetAlertWorker`** / **`AutoBackupWorker`**：两个 WorkManager 定时任务（预算预警每 6 小时检查一次、每日自动备份一次），均用 `KEEP` 策略入队，因此 `App.onCreate` 可以无条件同步而不重置计时。
- 去重与开关状态存在 SharedPreferences（后台需要同步读取）。
- 自动备份文件写在**应用专属外部目录**（`Android/data/<包名>/files/backups`），不需要存储权限、卸载即清理，外部存储不可用时退回内部目录。

### 数据持久化

- **Room**：`ledger_entries` 表 + `budgets` 表 + `chat_messages` 表。当前 schema 版本 **v4**。

  `ledger_entries` 字段：`id`(PK)、`amountCents`(Long)、`note`、`timeMillis`(Long)、`isExpense`(Boolean)、`categoryName`、`categoryIconRes`。
  金额以「分」存储避免浮点误差，时间以 epoch 毫秒存储以便索引与聚合。建有 4 个索引：

  | 索引 | 支撑的查询 |
  |---|---|
  | `timeMillis` | 首页 `ORDER BY timeMillis DESC` 翻页、日期区间筛选 |
  | `isExpense + timeMillis` | 今日 / 本月收支聚合、报表近 7 天分桶 |
  | `categoryName + timeMillis` | 分类筛选翻页、筛选弹窗的分类去重 |
  | `isExpense + categoryName + timeMillis` | 饼图（`isExpense=1` + 时间下界 + `GROUP BY categoryName`），可覆盖扫描 |

  `budgets` 表：`(periodType, categoryName)` 复合主键 + `limitCents`。预算存的是一条**长期生效的规则**，当期进度用当期支出实时算，不为每个周期建行。

  `chat_messages` 表：`id`(自增 PK)、`role`、`content`、`createdAt`、`draftJson`(可空)。**不加额外索引** —— 自增 INTEGER PRIMARY KEY 就是 rowid，`ORDER BY id` 天然有序。
  `draftJson` 存 AI 解析出的待确认账目，**它的非空性本身就是状态**（非空 ⟺ 还没确认），因此不需要状态列，也就不会出现「状态列与草稿内容互相矛盾」的中间态。确认或忽略时统一置为 NULL。

- **升级约定（重要）**：任何改动实体（字段 / 索引 / 表）的提交都必须**同时**完成三件事 —— 升 `AppDatabase` 版本号、补一条 `Migration`、把 KSP 导出的 `app/schemas/<版本>.json` 一并提交。缺少迁移会让老用户升级后启动即崩（`IllegalStateException: A migration from X to Y was required but not found`），导出的 schema 则用于代码评审时比对结构变化。

  现有迁移：`MIGRATION_1_2`（补 4 个索引）、`MIGRATION_2_3`（新增 `budgets` 表）、`MIGRATION_3_4`（新增 `chat_messages` 表）。

  > 写新增表的迁移时，建表 SQL 请**从构建产出的 `app/schemas/<版本>.json` 里逐字抄**（只把 `${TABLE_NAME}` 换成表名）。
  > `@PrimaryKey(autoGenerate = true)` 的 Long 主键生成的是 `` `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL ``，
  > **没有**独立的 `PRIMARY KEY(...)` 尾子句；可空列生成的是 `` `x` TEXT ``，**不带** `NOT NULL`。这两处手写极易写错，
  > 一旦与 Room 的预期不一致，老用户升级后首次启动就会直接抛异常。

- **DataStore Preferences**：保存自定义分类（含所选颜色）与自动记账开关。
- **SharedPreferences**：`ThemeSettings`（主题模式 + 动态取色）、`BudgetAlertSettings`（预警开关 + 去重状态）、`AutoBackupSettings`（备份开关），以及 `AiSettings`（DeepSeek API Key + 模型）。前三处需要后台或启动时**同步**读取，异步的 DataStore 做不到；`AiSettings` 单独用一份文件则是因为 API Key 必须能按文件排除出自动备份与换机迁移（见下方「说明」）。
- **备份格式**：JSON（`BackupData`，version 3），含条目、**预算规则列表**与自定义分类。读取 v2 及更早的备份时会用旧的单个 `budget` 值补一条月度总预算，保证老备份不丢预算。备份字段刻意声明为**可空** —— Gson 反序列化 Kotlin data class 时不执行构造器与默认值，缺失字段会是 `null` 而非默认值。**对话记录不进备份**：它是助手的使用痕迹而非账本数据。

---

## 目录结构

```
PersonalLedger/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                # 组件与权限声明
│   │   ├── java/com/example/personalledger/   # 48 个 Kotlin 源文件
│   │   │   ├── App.kt / MainActivity.kt                        # 启动与导航容器
│   │   │   ├── HomeFragment.kt / ReportFragment.kt             # 两个主页面
│   │   │   ├── AssistantFragment.kt                            # AI 助手页
│   │   │   ├── AddEntryBottomSheetDialogFragment.kt            # 记账 / 编辑表单
│   │   │   ├── MainViewModel.kt / ReportViewModel.kt           # 视图模型
│   │   │   ├── AssistantViewModel.kt                           # 助手视图模型
│   │   │   ├── LedgerRepository.kt / DataStoreManager.kt       # 数据仓库与设置
│   │   │   ├── AppDatabase.kt / LedgerEntryEntity.kt           # Room 数据库与实体
│   │   │   ├── ChatMessageEntity.kt / ChatMessage.kt           # 对话实体与模型
│   │   │   ├── LedgerEntryDao.kt / BudgetDao.kt / ChatMessageDao.kt  # DAO
│   │   │   ├── BudgetEntity.kt / BudgetRule.kt / BudgetStats.kt    # 预算规则与进度
│   │   │   ├── BudgetAlertSettings.kt / BudgetAlertNotifier.kt / BudgetAlertWorker.kt  # 预算预警
│   │   │   ├── AutoBackupWorker.kt                             # 自动本地备份
│   │   │   ├── LedgerStats.kt / HomeInsight.kt / LedgerPaging.kt   # 统计 / 洞察 / 分页（纯函数）
│   │   │   ├── LedgerDateTime.kt / CsvLedgerParser.kt          # 时间换算 / CSV 解析（纯函数）
│   │   │   ├── DeepSeekClient.kt                               # 唯一的联网代码
│   │   │   ├── AiBookkeepingParser.kt / AiPrompt.kt / DefaultCategories.kt  # 助手解析与提示词（纯函数）
│   │   │   ├── AiSettings.kt                                   # API Key 与模型
│   │   │   ├── LedgerNotificationListenerService.kt            # 通知监听服务
│   │   │   ├── NotificationPaymentParser.kt / AutoBookkeepingRules.kt  # 自动记账解析与规则
│   │   │   ├── LedgerItem.kt / LedgerItemMappers.kt / LedgerItemJsonAdapter.kt  # 数据模型与映射
│   │   │   ├── CategoryItem.kt / CategoryColors.kt / CategoryAdapter.kt         # 分类模型与配色
│   │   │   └── ThemeSettings.kt / ThemeColors.kt / LedgerAdapter.kt / ChatAdapter.kt  # 主题与列表适配
│   │   └── res/                                # 资源文件
│   │       ├── layout/                         # 布局（含助手页与对话条目）
│   │       ├── drawable/ + drawable-night/     # 图标与背景（含深色变体、聊天气泡）
│   │       ├── values/ + values-night/         # 颜色、主题、样式、文案
│   │       ├── font/                           # 内嵌 Noto Sans SC 字体
│   │       ├── menu/ + anim/ + color/ + xml/   # 菜单、动画、着色、备份规则
│   │       └── mipmap-*/                       # 各密度应用图标
│   ├── schemas/                                # KSP 导出的 Room schema（1/2/3/4.json，供对照与评审）
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

- 本仓库**不含可运行的云端服务或后端**，账本功能全部为本地离线实现。
- 应用申请 `POST_NOTIFICATIONS`（预算预警通知，Android 13+ 属运行时权限）与 `INTERNET`（仅 AI 助手使用，不配置 Key 时不会发出任何请求）。自动记账依赖系统的「通知使用权」，不是普通权限。
- **什么数据会离开设备**：只有 AI 助手用得到的三样东西 —— 你发送的消息文本、**最近 6 轮**对话、以及分类名称清单。账本记录（`ledger_entries`）本身**从不上传**。
- **API Key 的存放**：明文存于应用私有目录的独立 SharedPreferences 文件（`ai_prefs.xml`），并已在 `backup_rules.xml` 与 `data_extraction_rules.xml` 中**排除出云备份与换机迁移**。本项目不引入 `androidx.security` 做加密 —— 依赖成本与收益不成比例，改为靠「私有目录 + 排除备份」控制暴露面；Key 一旦泄露，请直接在 DeepSeek 平台吊销。
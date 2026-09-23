# PersonalLedger 数据库表设计（Markdown版）

说明：
- 当前版本账本条目与预算规则已迁移到 **Room**，实际表为 `ledger_entries`（见「表 3-2」，schema v3，含 4 个索引）与 `budgets`（见「表 3-4」），金额统一以“分”（`Long`）存储、时间以 epoch 毫秒存储；自定义分类、主题模式与各项开关仍由 **DataStore Preferences** 保存（即概念上等价于 `app_settings` 的轻量版本）。
- 本文档其余各表（`users`、`categories`、`daily_stats`、`monthly_stats`、`app_settings`、`operation_logs`）为按关系型数据库规范整理的**扩展设计**，用于说明后续从轻量本地存储升级到规范化数据层时的落地方案。
- 升级约定：任何改动实体（字段 / 索引 / 表）的提交都必须同时升 `AppDatabase` 版本号、补一条 `Migration`，并把 KSP 导出的 `app/schemas/<版本>.json` 一并提交。

## 表 3-1 用户表（`users`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 用户ID |
| 2 | username | varchar(64) | 非空，唯一 | 用户名 |
| 3 | password_hash | varchar(255) | 非空 | 密码摘要 |
| 4 | is_logged_in | tinyint(1) | 非空，默认0 | 登录状态 |
| 5 | created_at | timestamp | 非空，默认当前时间 | 创建时间 |
| 6 | updated_at | timestamp | 非空，默认当前时间 | 更新时间 |

## 表 3-2 账本条目表（`ledger_entries`，当前 Room 实体 `LedgerEntryEntity`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | text | 主键（UUID） | 条目ID |
| 2 | amount_cents | bigint | 非空 | 金额（单位：分，避免浮点误差） |
| 3 | note | text | 可空 | 备注 |
| 4 | time_millis | bigint | 非空 | 发生时间（epoch 毫秒） |
| 5 | is_expense | integer | 非空 | 收支类型（1支出/0收入） |
| 6 | category_name | text | 非空 | 分类名称 |
| 7 | category_icon_res | integer | 非空 | 分类图标资源 ID |

索引（4 个，每个都对应一类真实查询）：

| 索引 | 服务的查询 |
|---|---|
| `time_millis` | 首页默认分页 `ORDER BY timeMillis DESC`、日期区间筛选 |
| `is_expense + time_millis` | 首页今日 / 本月聚合、报表 7 天分桶、按收支筛选 |
| `category_name + time_millis` | 分类筛选翻页、首页分类去重候选 |
| `is_expense + category_name + time_millis` | 报表饼图 `GROUP BY category_name`、分类下钻 |

## 表 3-3 分类表（`categories`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 分类ID |
| 2 | user_id | bigint | 可空，外键->users.id | 所属用户（空=系统分类） |
| 3 | name | varchar(64) | 非空 | 分类名称 |
| 4 | icon_res | varchar(128) | 可空 | 图标资源标识 |
| 5 | category_type | varchar(16) | 非空 | 分类类型（expense/income） |
| 6 | is_default | tinyint(1) | 非空，默认0 | 是否默认分类 |
| 7 | created_at | timestamp | 非空，默认当前时间 | 创建时间 |

## 表 3-4 预算表（`budgets`，当前 Room 实体 `BudgetEntity`）

预算存的是一条**长期生效的规则**，而不是「每个周期一条记录」：「餐饮每月 1500」就是一条规则，
每个周期的进度都用**当期支出实时计算**，因此不需要为每个月建行，也不需要定期生成数据。

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | period_type | text | 复合主键，非空 | 周期类型（月 / 周），取值见 `BudgetPeriod.key` |
| 2 | category_name | text | 复合主键，非空 | 分类名；**空串**表示「不限分类」的总预算 |
| 3 | limit_cents | bigint | 非空 | 限额（单位：分，与账目金额同单位，避免浮点误差） |

> 主键用 `(period_type, category_name)` 复合键而不是自增 `id`：「同一周期 + 同一分类」在语义上
> 只能有一条规则，让主键直接表达这个约束，就不会出现重复规则。
>
> **关系型扩展设计（尚未实现）**：若将来引入多用户与「周期快照」需求，可改为
> `id` 自增主键 + `user_id` 外键 + `period_value`（如 `2026-05`）+ `budget_amount decimal(12,2)`
> + `created_at` / `updated_at`，为每个周期固化一行，便于按周期回看历史预算。
> 当前实现之所以不这么做，是因为「回看历史预算」目前没有需求，而存规则 + 实时计算
> 可以少维护一张会随周期不断增长的表。

## 表 3-5 每日统计表（`daily_stats`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 统计ID |
| 2 | user_id | bigint | 非空，外键->users.id | 用户ID |
| 3 | stat_date | date | 非空 | 统计日期 |
| 4 | income_total | decimal(12,2) | 非空，默认0.00 | 当日收入 |
| 5 | expense_total | decimal(12,2) | 非空，默认0.00 | 当日支出 |
| 6 | balance_total | decimal(12,2) | 非空，默认0.00 | 当日结余 |
| 7 | updated_at | timestamp | 非空，默认当前时间 | 更新时间 |

## 表 3-6 月度统计表（`monthly_stats`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 统计ID |
| 2 | user_id | bigint | 非空，外键->users.id | 用户ID |
| 3 | stat_month | varchar(7) | 非空 | 统计月份（YYYY-MM） |
| 4 | income_total | decimal(14,2) | 非空，默认0.00 | 月收入 |
| 5 | expense_total | decimal(14,2) | 非空，默认0.00 | 月支出 |
| 6 | budget_amount | decimal(12,2) | 可空 | 月预算 |
| 7 | budget_progress | decimal(5,2) | 可空 | 预算进度百分比 |
| 8 | updated_at | timestamp | 非空，默认当前时间 | 更新时间 |

## 表 3-7 系统设置表（`app_settings`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 设置ID |
| 2 | user_id | bigint | 非空，外键->users.id | 用户ID |
| 3 | setting_key | varchar(64) | 非空 | 设置键 |
| 4 | setting_value | text | 可空 | 设置值 |
| 5 | value_type | varchar(16) | 非空 | 值类型（string/int/bool/json） |
| 6 | updated_at | timestamp | 非空，默认当前时间 | 更新时间 |

## 表 3-8 操作日志表（`operation_logs`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 日志ID |
| 2 | user_id | bigint | 非空，外键->users.id | 用户ID |
| 3 | action_type | varchar(32) | 非空 | 操作类型（新增/删除/编辑/登录） |
| 4 | target_type | varchar(32) | 非空 | 对象类型 |
| 5 | target_id | bigint | 可空 | 对象ID |
| 6 | detail_json | text | 可空 | 操作明细 |
| 7 | created_at | timestamp | 非空，默认当前时间 | 操作时间 |

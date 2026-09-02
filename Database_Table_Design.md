# PersonalLedger 数据库表设计（Markdown版）

说明：
- 当前版本账本条目已迁移到 **Room**，实际表为 `ledger_entries`（见「表 3-2」），金额以“分”（`Long`）存储、时间以 epoch 毫秒存储；月预算与自定义分类仍由 **DataStore Preferences** 保存（即概念上等价于 `app_settings` 的轻量版本）。
- 本文档其余各表（`users`、`categories`、`budgets`、`daily_stats`、`monthly_stats`、`operation_logs`）为按关系型数据库规范整理的**扩展设计**，用于说明后续从轻量本地存储升级到规范化数据层时的落地方案。

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

## 表 3-4 预算表（`budgets`）

| 序号 | 字段名称 | 数据类型 | 字段约束 | 字段内容 |
|---|---|---|---|---|
| 1 | id | bigint | 主键，自增 | 预算ID |
| 2 | user_id | bigint | 非空，外键->users.id | 用户ID |
| 3 | period_type | varchar(16) | 非空 | 周期类型（月/周/日） |
| 4 | period_value | varchar(32) | 非空 | 周期值（如2026-05） |
| 5 | budget_amount | decimal(12,2) | 非空 | 预算金额 |
| 6 | created_at | timestamp | 非空，默认当前时间 | 创建时间 |
| 7 | updated_at | timestamp | 非空，默认当前时间 | 更新时间 |

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

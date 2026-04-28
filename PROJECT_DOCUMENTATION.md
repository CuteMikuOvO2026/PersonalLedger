# PersonalLedger 项目文档

## 1. 项目概述

### 1.1 项目名称
`PersonalLedger`（应用名：`轻账`）

### 1.2 项目定位
本项目是一款基于 Android 原生开发的个人记账应用，面向日常收支记录、预算控制与消费统计场景。用户可以通过应用快速记录收入和支出，查看近期账单，设置月度预算，并通过图表查看消费结构与资产变化趋势。

### 1.3 项目目标
- 提供简洁直观的个人记账体验
- 支持收入与支出双类型录入
- 支持分类管理，便于消费分析
- 支持月度预算设置与超支提醒
- 支持图表化报表展示
- 支持 CSV 导出与分享，方便账本备份和二次分析

## 2. 功能概览

### 2.1 首页记账模块
- 展示本月结余或本月超支情况
- 展示今日收入与今日支出
- 展示本月预算及预算使用进度
- 展示最近记录列表
- 支持点击悬浮按钮新增账单
- 支持长按账单删除记录

### 2.2 新增账单模块
- 使用 `BottomSheetDialogFragment` 作为录入面板
- 支持切换“支出 / 收入”类型
- 支持按类型切换分类列表
- 支持输入金额与备注
- 自动记录当前时间
- 对金额和分类进行合法性校验

### 2.3 预算管理模块
- 支持设置当月预算
- 根据当月支出自动计算预算使用比例
- 当预算使用率达到不同区间时切换进度条颜色
  - 小于 80%：正常
  - 80% 到 99%：预警
  - 100% 及以上：超支

### 2.4 报表分析模块
- 饼图：展示支出分类占比
- 柱状图：展示最近 7 天每日支出
- 折线图：展示账本累计结余变化趋势
- 支持导出账单为 CSV 文件
- 支持系统分享导出的账本文件
- 支持一键清空所有数据

## 3. 技术方案

### 3.1 开发环境
- 开发语言：Kotlin
- 构建工具：Gradle Kotlin DSL
- 最低支持版本：Android 7.0（API 24）
- 目标版本：Android API 36
- Java 版本：11

### 3.2 核心技术栈
- `AppCompat + Fragment`
- `ViewBinding`
- `AndroidX Lifecycle`
- `LiveData`
- `AndroidViewModel`
- `Kotlin Coroutines`
- `Jetpack DataStore Preferences`
- `Gson`
- `MPAndroidChart`
- `Material Components`

### 3.3 架构思路
项目整体采用轻量级分层设计，主要由以下部分组成：

- `UI 层`
  - `MainActivity`
  - `HomeFragment`
  - `ReportFragment`
  - `AddEntryBottomSheetDialogFragment`
  - `RecyclerView Adapter`

- `状态与业务层`
  - `MainViewModel`

- `数据持久化层`
  - `DataStoreManager`

该结构以 `ViewModel + LiveData + DataStore` 为核心，实现界面与数据状态联动，逻辑清晰，适合中小型本地应用。

## 4. 页面设计

### 4.1 主界面 MainActivity
主界面负责：
- 初始化应用主布局
- 承载顶部工具栏和底部导航栏
- 默认加载首页 `HomeFragment`
- 在“记账”和“报表”页面之间切换
- 配置页面切换动画

### 4.2 首页 HomeFragment
首页主要承担“记账总览”和“快速操作”功能：
- 读取并展示历史账单
- 统计本月结余/超支
- 统计今日收入、今日支出
- 展示预算金额和预算使用进度
- 响应新增记录与删除记录操作
- 弹出预算设置对话框

### 4.3 报表页 ReportFragment
报表页主要承担“数据分析”功能：
- 初始化并更新三类图表
- 生成分类支出饼图数据
- 生成近 7 日支出柱状图数据
- 生成累计结余折线图数据
- 导出 CSV
- 分享导出文件
- 执行数据清空操作

### 4.4 记账弹窗 AddEntryBottomSheetDialogFragment
弹窗负责账单录入：
- 金额输入
- 备注输入
- 收支类型切换
- 分类选择
- 数据校验
- 提交到 `MainViewModel`

## 5. 数据设计

### 5.1 账单实体 LedgerItem
核心账单实体包含以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `amount` | `String` | 金额，保存为格式化字符串 |
| `note` | `String` | 备注 |
| `time` | `String` | 记录时间，格式为 `yyyy-MM-dd HH:mm` |
| `isExpense` | `Boolean` | 是否为支出，`true` 为支出，`false` 为收入 |
| `categoryName` | `String` | 分类名称 |
| `categoryIconRes` | `Int` | 分类图标资源 ID |

### 5.2 分类数据
项目内置两组分类：

- 支出分类
  - 餐饮
  - 交通
  - 购物
  - 娱乐
  - 医疗
  - 教育
  - 住房
  - 其他

- 收入分类
  - 工资
  - 奖金
  - 投资
  - 兼职
  - 其他

### 5.3 本地存储设计
项目使用 `Jetpack DataStore Preferences` 做本地持久化，不依赖数据库。

主要存储项如下：

| Key | 类型 | 说明 |
| --- | --- | --- |
| `ledger_amount_decimal` | `Double` | 当前累计资产值 |
| `ledger_history_list` | `String` | 账单列表 JSON |
| `ledger_budget_decimal` | `Double` | 当前预算金额 |

同时保留了历史整型字段兼容逻辑：
- `ledger_amount`
- `ledger_budget`

这意味着项目在数据层对旧版本数据做了兼容迁移处理。

## 6. 核心业务逻辑

### 6.1 收支累计逻辑
新增账单时：
- 若为支出，则累计资产减少
- 若为收入，则累计资产增加

在当前实现中，`amountFlow` 表示的是“净资产差额”的镜像值：
- 添加支出时增加内部累计值
- 添加收入时减少内部累计值
- 在图表与首页统计中再按业务含义重新计算

从页面展示上，首页实际通过遍历历史记录计算“本月收入 - 本月支出”来得到本月结余，因此界面表现是正确的。

### 6.2 每日与每月统计逻辑
`MainViewModel` 中实现了以下统计方法：
- 今日收入统计
- 今日支出统计
- 本月支出统计
- 总收入统计
- 总支出统计

统计方式基于账单时间字符串前缀匹配：
- `yyyy-MM-dd` 用于当天统计
- `yyyy-MM` 用于当月统计

### 6.3 预算进度逻辑
预算进度计算公式：

```text
本月支出 / 当前预算 * 100%
```

并将结果限制在 `0% ~ 100%` 之间用于进度条显示。

### 6.4 报表数据生成逻辑

#### 饼图
- 遍历所有支出账单
- 按分类聚合金额
- 转换为 `PieEntry`

#### 柱状图
- 生成最近 7 天日期范围
- 从支出账单中按日期分组
- 汇总每天支出金额
- 转换为 `BarEntry`

#### 折线图
- 按账单时间排序
- 从第一条记录开始累计余额变化
- 支出记为负，收入记为正
- 转换为 `Entry`

## 7. 项目目录结构

```text
PersonalLedger/
├─ app/
│  ├─ src/main/
│  │  ├─ java/com/example/personalledger/
│  │  │  ├─ MainActivity.kt
│  │  │  ├─ MainViewModel.kt
│  │  │  ├─ HomeFragment.kt
│  │  │  ├─ ReportFragment.kt
│  │  │  ├─ AddEntryBottomSheetDialogFragment.kt
│  │  │  ├─ DataStoreManager.kt
│  │  │  ├─ LedgerItem.kt
│  │  │  ├─ Category.kt
│  │  │  ├─ CategoryItem.kt
│  │  │  ├─ LedgerAdapter.kt
│  │  │  └─ CategoryAdapter.kt
│  │  ├─ res/
│  │  │  ├─ layout/
│  │  │  ├─ drawable/
│  │  │  ├─ menu/
│  │  │  ├─ values/
│  │  │  ├─ font/
│  │  │  └─ anim/
│  │  └─ AndroidManifest.xml
│  └─ build.gradle.kts
├─ gradle/
├─ build.gradle.kts
├─ settings.gradle.kts
└─ README.md
```

## 8. 关键类职责说明

### 8.1 MainActivity
- 负责应用入口和页面容器管理
- 负责底部导航切换
- 负责工具栏设置和沉浸式状态栏配置

### 8.2 MainViewModel
- 负责连接 UI 与数据层
- 对外暴露页面所需的 `LiveData`
- 负责新增、删除、预算保存、重置等业务操作
- 负责生成报表图表所需的数据集

### 8.3 DataStoreManager
- 负责本地数据读写
- 负责账单列表的 JSON 序列化与反序列化
- 负责旧字段兼容

### 8.4 HomeFragment
- 负责首页数据展示与交互
- 负责预算设置和删除确认逻辑

### 8.5 ReportFragment
- 负责图表初始化与刷新
- 负责导出与分享逻辑
- 负责清空数据逻辑

### 8.6 AddEntryBottomSheetDialogFragment
- 负责账单录入表单
- 负责用户输入校验
- 负责分类切换和保存动作

## 9. UI 与交互特点

### 9.1 视觉风格
- 使用 Material Design 组件
- 深色主题风格明显
- 首页统计卡片和报表卡片具有统一视觉层次
- 收入、支出、预警分别使用不同强调色

### 9.2 交互特点
- 使用底部导航进行主功能切换
- 使用底部弹窗进行快速记账
- 使用长按删除记录，减少列表界面按钮干扰
- 使用图表增强数据可视化表达
- 使用系统文档创建器导出 CSV，符合 Android 文件访问规范

## 10. 构建与运行方式

### 10.1 运行环境要求
- Android Studio 最新稳定版或兼容版本
- JDK 11
- Android SDK 24 及以上运行环境

### 10.2 本地运行步骤
1. 使用 Android Studio 打开项目根目录 `PersonalLedger`
2. 等待 Gradle 同步完成
3. 连接真机或启动模拟器
4. 运行 `app` 模块

### 10.3 构建 APK
可通过 Android Studio 图形界面构建，或执行 Gradle 命令：

```powershell
.\gradlew assembleRelease
```

项目目录中已存在一次构建产物：
- `app/release/app-release.apk`

## 11. 项目亮点

- 功能闭环完整，覆盖记账、统计、预算、导出、分享
- 使用 `DataStore` 替代传统 `SharedPreferences`，实现更现代的本地存储方案
- 使用 `MPAndroidChart` 提升数据展示效果
- 使用 `ViewBinding` 降低空指针风险
- 使用 `BottomSheetDialogFragment` 提升移动端输入体验
- 保留旧字段兼容逻辑，体现了一定的数据迁移意识

## 12. 当前已知限制与可优化方向

### 12.1 当前限制
- 数据仅保存在本地，不支持云同步
- 账单数据使用 JSON 字符串整体存储，不适合大规模数据场景
- 暂无搜索、筛选、编辑账单功能
- 暂无多账本、多用户与登录体系
- 测试代码目前仍为默认模板，业务测试覆盖不足

### 12.2 可优化方向
- 引入 `Room` 数据库提升结构化存储能力
- 增加账单编辑、筛选、按月切换查询等能力
- 增加预算超支通知提醒
- 增加数据备份与恢复功能
- 增加云同步或账号体系
- 增加单元测试和 UI 自动化测试
- 优化 `LiveData.observeForever` 的使用方式，降低潜在生命周期风险
- 将金额字段统一为数值类型，避免重复格式转换

## 13. 适用场景

本项目适用于以下场景：
- Android 原生开发课程设计
- 个人毕业设计作品展示
- 本地离线记账类应用原型
- Jetpack 基础组件综合练习项目

## 14. 总结

`PersonalLedger` 是一个完成度较高的 Android 本地记账应用，具备清晰的页面结构、完整的记账闭环和较好的数据可视化能力。项目采用 Kotlin、ViewBinding、ViewModel、DataStore 与图表组件完成核心功能实现，整体技术路线合理，适合作为课程项目、毕业设计作品或个人作品集展示项目。

如果后续继续演进，可以围绕“数据结构升级、功能完善、测试补齐、云端能力接入”四个方向继续提升项目成熟度。

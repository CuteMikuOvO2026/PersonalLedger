from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION_START
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "PersonalLedger_项目总结.docx"


def set_cell_shading(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), fill)
    tc_pr.append(shd)


def set_font(run, size=None, bold=False, color=None):
    run.font.name = "Noto Sans SC"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "Noto Sans SC")
    run._element.rPr.rFonts.set(qn("w:ascii"), "Noto Sans SC")
    run._element.rPr.rFonts.set(qn("w:hAnsi"), "Noto Sans SC")
    run.bold = bold
    if size:
        run.font.size = Pt(size)
    if color:
        run.font.color.rgb = RGBColor.from_string(color)


def style_paragraph(paragraph, size=10.5, bold=False, color="1F2937"):
    for run in paragraph.runs:
        set_font(run, size=size, bold=bold, color=color)


def add_bullet(doc, text):
    p = doc.add_paragraph(style="List Bullet")
    run = p.add_run(text)
    set_font(run, size=10.5, color="334155")
    p.paragraph_format.space_after = Pt(2)
    return p


def add_heading(doc, text, level):
    p = doc.add_paragraph(style=f"Heading {level}")
    run = p.add_run(text)
    set_font(run, size=16 if level == 1 else 12.5, bold=True, color="163A5F")
    p.paragraph_format.space_before = Pt(10 if level == 1 else 6)
    p.paragraph_format.space_after = Pt(4)
    return p


def add_body(doc, text):
    p = doc.add_paragraph()
    run = p.add_run(text)
    set_font(run, size=10.5, color="334155")
    p.paragraph_format.line_spacing = 1.35
    p.paragraph_format.space_after = Pt(6)
    return p


def add_table(doc, headers, rows):
    table = doc.add_table(rows=1, cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.style = "Table Grid"
    hdr = table.rows[0].cells
    for i, header in enumerate(headers):
        hdr[i].text = header
        set_cell_shading(hdr[i], "DCEAF7")
        for p in hdr[i].paragraphs:
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            style_paragraph(p, size=10, bold=True, color="163A5F")
    for row in rows:
        cells = table.add_row().cells
        for i, value in enumerate(row):
            cells[i].text = value
            for p in cells[i].paragraphs:
                style_paragraph(p, size=9.8, color="334155")
    doc.add_paragraph()
    return table


doc = Document()
section = doc.sections[0]
section.top_margin = Cm(2.0)
section.bottom_margin = Cm(2.0)
section.left_margin = Cm(2.1)
section.right_margin = Cm(2.1)

title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = title.add_run("PersonalLedger 项目总结")
set_font(r, size=20, bold=True, color="0F3D66")

subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = subtitle.add_run("Android 个人记账应用 · 项目分析与文档归档")
set_font(r, size=10.5, color="5B7083")
subtitle.paragraph_format.space_after = Pt(14)

meta = doc.add_table(rows=2, cols=2)
meta.alignment = WD_TABLE_ALIGNMENT.CENTER
meta.style = "Table Grid"
meta.cell(0, 0).text = "仓库名称"
meta.cell(0, 1).text = "PersonalLedger"
meta.cell(1, 0).text = "分析范围"
meta.cell(1, 1).text = "README、Gradle 配置、AndroidManifest、核心 Kotlin 源码、布局资源与数据库设计说明"
for row in meta.rows:
    for idx, cell in enumerate(row.cells):
        if idx == 0:
            set_cell_shading(cell, "EAF2F8")
        for p in cell.paragraphs:
            style_paragraph(p, size=10, bold=(idx == 0), color="334155")
doc.add_paragraph()

add_heading(doc, "1. 项目概述", 1)
add_body(doc, "PersonalLedger 是一个基于 Android 原生技术栈开发的个人记账应用，面向日常收支记录、预算控制和消费复盘场景。项目采用单模块结构，以 Activity + Fragment 构建界面，以 ViewModel 管理状态，并以 Room 为主、DataStore Preferences 与 SharedPreferences 为辅完成本地持久化，全程离线、无账号体系、不依赖任何后端服务。")
add_body(doc, "从当前实现来看，应用已经覆盖收支录入（含时间可编辑与补记）、分类与自定义分类、分页与多条件筛选、分类 / 周期预算与预算提醒、图表报表与下钻、CSV 导入导出、PDF 导出、JSON 备份与每日自动本地备份等完整闭环，具备课程设计、毕业设计或小型独立应用展示的较强完整度。")

add_heading(doc, "2. 技术栈与工程结构", 1)
add_table(
    doc,
    ["维度", "当前实现"],
    [
        ["开发平台", "Android 原生应用"],
        ["语言", "Kotlin + 少量 XML 资源配置"],
        ["构建工具", "Gradle Kotlin DSL"],
        ["最低/目标 SDK", "minSdk 24 / targetSdk 36"],
        ["架构核心", "Activity + Fragment + AndroidViewModel"],
        ["状态与持久化", "Room（账本条目 + 预算规则，schema v3）+ DataStore Preferences / SharedPreferences（自定义分类、主题、开关）"],
        ["图表能力", "MPAndroidChart（饼图 / 柱状图，含时间范围筛选与点击下钻）"],
        ["后台任务", "WorkManager（预算预警检查、每日自动本地备份）"],
        ["界面能力", "ViewBinding + Material 3 风格组件（浅色 / 深色 / 跟随系统，可选 Material You 动态取色）"],
        ["测试", "JUnit 单元测试 + Room MigrationTestHelper 仪器化迁移测试"],
    ],
)
add_bullet(doc, "项目为单模块结构，入口模块为 `app`，便于理解和演示。")
add_bullet(doc, "构建配置简洁，第三方依赖数量可控，适合教学项目或个人练手项目。")
add_bullet(doc, "资源层包含自定义配色、卡片背景、底部导航图标、分类图标和中文字体资源，说明项目对视觉表达有一定投入。")

add_heading(doc, "3. 主要功能模块", 1)
add_table(
    doc,
    ["模块", "职责说明", "关键文件"],
    [
        ["主页记账", "展示当日收入、当日支出、本月结余与近期账单", "HomeFragment.kt"],
        ["账单录入", "底部弹窗录入金额、分类、备注，并支持编辑已有记录", "AddEntryBottomSheetDialogFragment.kt"],
        ["账单列表", "展示记录、滑动展开编辑/删除、删除撤销", "LedgerAdapter.kt / item_ledger.xml"],
        ["搜索与筛选", "按备注搜索，并按类型/分类/金额区间/日期区间筛选", "MainViewModel.kt / HomeFragment.kt"],
        ["预算管理", "支持多条预算规则（不限分类的总预算或按分类限额，周期可选每月 / 每周），按当期支出实时计算进度并在 80% / 100% 处预警", "HomeFragment.kt / BudgetStats.kt / BudgetDao.kt"],
        ["预算提醒", "定时检查预算使用情况，达到预警或超支时发通知，同一周期内不重复提醒", "BudgetAlertWorker.kt / BudgetAlertNotifier.kt"],
        ["首页洞察", "一张卡给出本月最高支出分类、日均支出与上月环比", "HomeInsight.kt"],
        ["报表分析", "饼图与近 7 日柱状图呈现消费结构，饼图支持时间范围筛选，点击柱子或分类可下钻查看明细", "ReportFragment.kt / ReportViewModel.kt"],
        ["数据导出", "导出 CSV（带 UTF-8 BOM，兼容 Excel）与 PDF，并支持 JSON 备份导出与导入恢复", "ReportFragment.kt"],
        ["数据导入", "可导入本应用导出的 CSV，也可导入其他记账 App 的 CSV，提供列映射确认，按追加方式导入", "CsvLedgerParser.kt"],
        ["自动本地备份", "每日把完整备份写入应用专属目录，保留最近 5 份并可选任意一份恢复，全程不涉及网络", "AutoBackupWorker.kt"],
    ],
)

add_heading(doc, "4. 核心业务流程", 1)
add_bullet(doc, "应用启动后直接进入记账主页，通过底部导航在「记账」与「报表」两个核心页面间切换。")
add_bullet(doc, "用户在底部弹窗中选择收入或支出类型，输入金额、分类与备注后保存，既支持新增也支持编辑。")
add_bullet(doc, "ViewModel 根据收支方向同步更新总额、历史列表、当日统计、本月支出和预算进度。")
add_bullet(doc, "主页负责展示概览信息与最近记录，并支持搜索与多条件筛选；报表页负责聚合分析结果并生成图表。")
add_bullet(doc, "报表页与主页菜单支持导出 CSV / PDF 以及 JSON 备份导出和导入恢复。")

add_heading(doc, "5. 数据模型与持久化设计", 1)
add_body(doc, "项目采用混合持久化：账本条目与预算规则使用 Room（当前 schema 版本 v3），自定义分类、主题模式与各项开关使用 DataStore Preferences，需要后台同步读取的少量状态放在 SharedPreferences。整体轻量、可靠，且能避免浮点误差。")
add_table(
    doc,
    ["数据项", "保存方式", "说明"],
    [
        ["账本条目", "Room `ledger_entries`", "金额以“分”存储、时间以 epoch 毫秒存储；建有 4 个索引支撑分页排序、日期筛选与报表分组"],
        ["预算规则", "Room `budgets`", "以 (periodType, categoryName) 复合主键表达「同一周期同一分类只有一条」；存的是长期生效的规则，而非每周期一条记录"],
        ["自定义分类", "DataStore Preferences", "以 JSON 保存 `List<CategoryItem>`，含用户自选颜色"],
        ["主题与开关", "DataStore Preferences + SharedPreferences", "主题模式另用 SharedPreferences 即时镜像，便于启动时同步读取"],
    ],
)
add_body(doc, "升级约定：任何改动实体（字段 / 索引 / 表）的提交都必须同时升 `AppDatabase` 版本号、补一条 `Migration`，并把 KSP 导出的 `app/schemas/<版本>.json` 一并提交；`AppDatabaseMigrationTest` 会用 `MigrationTestHelper` 逐段校验迁移结果与导出的 schema 完全一致。")
add_body(doc, "仓库还提供了一份 `Database_Table_Design.md`，在 Room 实际表结构基础上，整理了用户表、分类表、预算表、统计表和操作日志表等关系型扩展设计，说明项目作者已经考虑到未来从轻量本地存储迁移到更规范化数据库设计的可扩展路径。")

add_heading(doc, "6. 架构特点与实现亮点", 1)
add_bullet(doc, "首页由 `MainViewModel` 统一管理，报表页拆出独立的 `ReportViewModel`，两侧的聚合各自下推到 Room，避免报表统计拖累首页。")
add_bullet(doc, "筛选、排序、分页（`LIMIT/OFFSET`）、首页收支聚合与报表分组全部下推到 Room `@Query`；刻意不提供「整表响应式读取」入口，只有备份与导出用一次性挂起函数取全量。")
add_bullet(doc, "报表页基于 MPAndroidChart 实现饼图与近 7 日柱状图，支持时间范围筛选与点击下钻，数据可视化完整。")
add_bullet(doc, "统计与预算的时间窗口、阈值与聚合换算都抽成纯函数（`LedgerStats` / `BudgetStats` / `HomeInsight` / `LedgerDateTime` / `CsvLedgerParser` / `LedgerPaging`），并保留内存版实现与 SQL 聚合结果**对拍**，保证两条路径数字一致。")
add_bullet(doc, "账单列表支持滑动展开编辑/删除操作，交互体验比基础列表更丰富。")
add_bullet(doc, "CSV 导出结合 Android 系统文档创建与分享流程，具备实际可用性。")

add_heading(doc, "7. 当前局限与改进建议", 1)
add_table(
    doc,
    ["方面", "当前情况", "建议方向"],
    [
        ["账户安全", "无账号体系，数据完全保存在本地", "若未来接入账号，需引入加密存储或正式认证体系"],
        ["多用户能力", "当前更接近单机单用户模式", "按用户隔离账本数据并扩展账号体系"],
        ["数据规模", "已迁移到 Room，首页分页与聚合均下推 SQL 并建有 4 个索引", "若账本涨到十万行以上，可重新评估覆盖索引与历史数据归档策略"],
        ["统计维度", "覆盖日、月、近 7 日、分类占比与任意日期区间筛选", "可继续增加同比 / 环比趋势与多周期对比视图"],
        ["测试保障", "98 个单元测试（统计 / 预算 / 洞察 / CSV / 时间换算 / 分页）+ Room 迁移仪器化测试", "迁移测试尚未在真机或模拟器上执行过；通知解析（`parsePayment` / `detectDirection`）仍缺单测"],
        ["编码一致性", "部分中文资源存在编码异常痕迹", "统一 UTF-8 编码并清理乱码文本"],
    ],
)

add_heading(doc, "8. 综合评价", 1)
add_body(doc, "总体来看，PersonalLedger 是一个完成度较高的 Android 个人记账项目。它不仅实现了基础的账单记录，还延伸到预算约束、统计图表和数据导出，已经具备较清晰的产品闭环。对教学展示而言，它的工程结构明确、界面较完整、功能点覆盖较全，能够较好体现 Android 原生应用开发能力。")
add_body(doc, "若后续继续演进，最值得优先投入的方向是数据层升级、安全性提升和测试体系建设。完成这些工作后，项目将更接近可长期维护和真实交付的移动端应用。")

footer_section = doc.sections[-1]
footer = footer_section.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
fr = footer.add_run("PersonalLedger 项目总结文档")
set_font(fr, size=9, color="6B7280")

doc.save(OUTPUT)
print(OUTPUT)

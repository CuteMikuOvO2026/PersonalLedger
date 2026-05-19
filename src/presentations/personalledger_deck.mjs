import path from "node:path";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const artifact = await import(
  "file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs"
);
const { Canvas } = await import(
  "file:///C:/Users/ovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/node_modules/skia-canvas/lib/index.js"
);

const {
  Presentation,
  PresentationFile,
  column,
  row,
  grid,
  layers,
  panel,
  text,
  image,
  shape,
  rule,
  fill,
  hug,
  fixed,
  wrap,
  fr,
  auto,
  drawSlideToCtx,
} = artifact;

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..", "..");
const assetsDir = path.join(repoRoot, "src", "presentations", "assets");
const outDir = path.join(repoRoot, "output", "presentations", "personalledger");
const previewDir = path.join(outDir, "previews");

const W = 1920;
const H = 1080;

const COLORS = {
  bg: "#13161D",
  bgSoft: "#171B23",
  surface: "#1B202A",
  surfaceHi: "#222835",
  outline: "#2A3140",
  blue: "#5E7CE2",
  blueSoft: "#8EA3D6",
  teal: "#7AC7B6",
  green: "#71D6B5",
  red: "#FF8B8B",
  orange: "#FFB86B",
  text: "#F4F7FB",
  textSoft: "#97A4BA",
  textMuted: "#6D778A",
};

const sourceNote = "来源：项目总结、毕业论文、PersonalLedger 代码仓库";

const deck = Presentation.create({
  slideSize: { width: W, height: H },
});

function t(value, style = {}, options = {}) {
  return text(value, {
    width: options.width ?? fill,
    height: options.height ?? hug,
    ...options,
    style,
  });
}

function caption(value) {
  return t(
    value,
    { fontSize: 18, color: COLORS.textSoft },
    { width: fill, height: hug }
  );
}

function kicker(value, color = COLORS.blue) {
  return panel(
    {
      width: hug,
      height: hug,
      padding: { x: 18, y: 8 },
      fill: `${color}20`,
      borderRadius: 999,
    },
    t(value, { fontSize: 18, bold: true, color }, { width: hug, height: hug })
  );
}

function infoCard(title, lines, accent = COLORS.blue) {
  return panel(
    {
      width: fill,
      height: fill,
      padding: 28,
      fill: COLORS.surface,
      borderRadius: 26,
    },
    column({ width: fill, height: fill, gap: 14 }, [
      shape({
        width: fixed(56),
        height: fixed(6),
        fill: accent,
        borderRadius: 999,
      }),
      t(title, { fontSize: 30, bold: true, color: COLORS.text }),
      ...lines.map((line) =>
        t(line, { fontSize: 21, color: COLORS.textSoft }, { width: fill, height: hug })
      ),
    ])
  );
}

function phoneShot(imgPath, label, height = 360) {
  return column({ width: fill, height: hug, gap: 14 }, [
    panel(
      {
        width: fill,
        height: fixed(height),
        padding: 18,
        fill: COLORS.surface,
        borderRadius: 28,
      },
      image({
        path: imgPath,
        width: fill,
        height: fill,
        fit: "contain",
        borderRadius: 20,
        alt: label,
      })
    ),
    t(label, { fontSize: 20, color: COLORS.textSoft }, { width: fill, height: hug }),
  ]);
}

function footer() {
  return t(sourceNote, { fontSize: 13, color: COLORS.textMuted }, { width: fill, height: hug });
}

async function assetData(filePath, contentType) {
  const bytes = await fs.readFile(filePath);
  return {
    dataUrl: `data:${contentType};base64,${bytes.toString("base64")}`,
    contentType,
  };
}

function slide(root) {
  const s = deck.slides.add();
  s.compose(root, {
    frame: { left: 0, top: 0, width: W, height: H },
    baseUnit: 8,
  });
  return s;
}

const schoolLogo = await assetData(path.join(assetsDir, "school_logo.png"), "image/png");
const homeCard = await assetData(path.join(assetsDir, "home_card.png"), "image/png");
const reportScreen = await assetData(path.join(assetsDir, "report_screen.jpg"), "image/jpeg");
const loginScreen = await assetData(path.join(assetsDir, "login_screen.jpg"), "image/jpeg");
const entryDialog = await assetData(path.join(assetsDir, "entry_dialog.jpg"), "image/jpeg");
const budgetProgress = await assetData(path.join(assetsDir, "budget_progress.jpg"), "image/jpeg");
const androidStudio = await assetData(path.join(assetsDir, "android_studio.png"), "image/png");

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    shape({
      width: fixed(720),
      height: fill,
      fill: "#17243C",
      borderRadius: 0,
    }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(1.08), fr(0.92)],
        rows: [auto, fr(1), auto],
        columnGap: 44,
        rowGap: 28,
        padding: { x: 84, y: 64 },
      },
      [
        row({ width: fill, height: hug, align: "center", gap: 18 }, [
          image({
            path: schoolLogo,
            width: fixed(124),
            height: fixed(44),
            fit: "contain",
            alt: "school logo",
          }),
          kicker("Android 原生毕业设计项目", COLORS.teal),
        ]),
        column(
          {
            width: fill,
            height: fill,
            gap: 22,
            columnSpan: 1,
            rowSpan: 2,
            justify: "center",
          },
          [
            t("基于 Kotlin 与 Jetpack 架构的个人账本 App 的设计与实现", {
              fontSize: 58,
              bold: true,
              color: COLORS.text,
            }, { width: wrap(860), height: hug }),
            t(
              "PersonalLedger 项目汇报",
              { fontSize: 28, color: COLORS.blueSoft },
              { width: fill, height: hug }
            ),
            rule({ width: fixed(220), stroke: COLORS.teal, weight: 5 }),
            t(
              "围绕轻量化、本地化、数据可视化三个目标，完成登录认证、账单录入、预算管理、报表统计与 CSV 导出的一体化闭环。",
              { fontSize: 24, color: COLORS.textSoft },
              { width: wrap(840), height: hug }
            ),
            row({ width: fill, height: hug, gap: 18 }, [
              kicker("作者：王禹鑫"),
              kicker("学号：202251280", COLORS.orange),
              kicker("专业：软件工程", COLORS.teal),
            ]),
          ]
        ),
        column({ width: fill, height: fill, gap: 18, justify: "center" }, [
          phoneShot(homeCard, "主页总览", 228),
          phoneShot(reportScreen, "报表统计", 228),
          phoneShot(entryDialog, "记一笔弹窗", 186),
        ]),
        row(
          { width: fill, height: hug, columnSpan: 2, justify: "between", align: "end" },
          [
            t("Kotlin / Jetpack / MVVM / DataStore / MPAndroidChart", {
              fontSize: 18,
              color: COLORS.textMuted,
            }),
            t("PersonalLedger", { fontSize: 18, color: COLORS.textMuted }),
          ]
        ),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(0.95), fr(1.05)],
        rows: [auto, fr(1), auto],
        columnGap: 48,
        rowGap: 30,
        padding: { x: 88, y: 72 },
      },
      [
        column({ width: fill, height: hug, gap: 14, columnSpan: 2 }, [
          kicker("项目定位"),
          t("为什么要做一款本地个人账本 App？", {
            fontSize: 48,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(1100), height: hug }),
        ]),
        column({ width: fill, height: fill, gap: 22 }, [
          t("问题背景", { fontSize: 26, bold: true, color: COLORS.text }),
          t(
            "论文与项目总结共同指出，市面记账产品普遍存在功能冗余、广告干扰和隐私顾虑，而个人日常财务记录更需要一款轻量、离线、上手快的工具。",
            { fontSize: 25, color: COLORS.textSoft },
            { width: wrap(760), height: hug }
          ),
          row({ width: fill, height: hug, gap: 16 }, [
            kicker("纯本地运行", COLORS.green),
            kicker("无网络依赖", COLORS.orange),
            kicker("面向日常记账", COLORS.teal),
          ]),
          panel(
            {
              width: fill,
              height: hug,
              padding: 28,
              fill: "#192334",
              borderRadius: 26,
            },
            t(
              "核心目标：把“记账、看预算、做复盘”压缩在一个清晰的操作路径里，而不是做成一个复杂金融平台。",
              { fontSize: 28, bold: true, color: COLORS.text },
              { width: fill, height: hug }
            )
          ),
        ]),
        column({ width: fill, height: fill, gap: 18 }, [
          infoCard("轻量化", ["单模块结构，依赖可控", "不引入云服务和复杂后端"], COLORS.blue),
          infoCard("本地化与隐私", ["数据保存在设备端", "避免上传账单与账户信息"], COLORS.teal),
          infoCard("可视化与反馈", ["预算进度即时更新", "图表帮助用户复盘消费结构"], COLORS.orange),
        ]),
        footer(),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(1), fr(1), fr(1), fr(1)],
        rows: [auto, auto, fr(1), auto],
        columnGap: 22,
        rowGap: 26,
        padding: { x: 80, y: 70 },
      },
      [
        column({ width: fill, height: hug, gap: 12, columnSpan: 4 }, [
          kicker("技术架构"),
          t("从界面到存储的实现链路", {
            fontSize: 48,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(960), height: hug }),
          caption("项目采用 Kotlin + Jetpack + MVVM 组合，以共享 ViewModel 驱动首页与报表页同步。"),
        ]),
        infoCard("界面层", [
          "AuthActivity、MainActivity",
          "HomeFragment、ReportFragment",
          "底部弹窗完成记账录入",
        ], COLORS.blue),
        infoCard("状态层", [
          "MainViewModel 统一管理状态",
          "LiveData 驱动界面刷新",
          "MediatorLiveData 计算预算进度",
        ], COLORS.teal),
        infoCard("数据层", [
          "DataStoreManager 封装读写",
          "Gson 序列化账单列表",
          "本地离线持久化",
        ], COLORS.orange),
        infoCard("可视化层", [
          "MPAndroidChart 实现饼图",
          "近 7 日柱状图与余额折线图",
          "报表页支持 CSV 导出分享",
        ], COLORS.green),
        row({ width: fill, height: hug, gap: 16, columnSpan: 4, justify: "between" }, [
          kicker("Activity + Fragment"),
          t("→", { fontSize: 40, bold: true, color: COLORS.textMuted }, { width: hug }),
          kicker("MainViewModel"),
          t("→", { fontSize: 40, bold: true, color: COLORS.textMuted }, { width: hug }),
          kicker("DataStore Preferences + Gson JSON", COLORS.orange),
          t("→", { fontSize: 40, bold: true, color: COLORS.textMuted }, { width: hug }),
          kicker("图表 / 导出 / 预算反馈", COLORS.teal),
        ]),
        footer(),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    column(
      { width: fill, height: fill, padding: { x: 82, y: 66 }, gap: 28 },
      [
        column({ width: fill, height: hug, gap: 10 }, [
          kicker("功能闭环"),
          t("一套完整的个人记账使用流程", {
            fontSize: 48,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(1000), height: hug }),
          caption("项目不是单点功能演示，而是从身份认证到数据导出的完整业务链路。"),
        ]),
        row({ width: fill, height: fill, gap: 18, align: "start" }, [
          column({ width: fill, height: fill, gap: 12 }, [
            phoneShot(loginScreen, "1. 登录 / 注册", 450),
            caption("首次使用完成注册，后续根据本地登录状态直达主页。"),
          ]),
          column({ width: fill, height: fill, gap: 12 }, [
            phoneShot(homeCard, "2. 首页总览", 450),
            caption("查看本月结余、今日收支与近期账单。"),
          ]),
          column({ width: fill, height: fill, gap: 12 }, [
            phoneShot(entryDialog, "3. 账单录入", 450),
            caption("选择收支类型、金额、分类与备注后即时保存。"),
          ]),
          column({ width: fill, height: fill, gap: 12 }, [
            phoneShot(reportScreen, "4. 报表统计", 450),
            caption("饼图、柱状图、折线图帮助用户做消费复盘。"),
          ]),
          column({ width: fill, height: fill, gap: 12 }, [
            phoneShot(budgetProgress, "5. 预算预警", 450),
            caption("随着支出增长，进度条颜色变化形成直观提醒。"),
          ]),
        ]),
        footer(),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(0.92), fr(1.08)],
        rows: [auto, fr(1), auto],
        columnGap: 34,
        rowGap: 24,
        padding: { x: 84, y: 68 },
      },
      [
        column({ width: fill, height: hug, gap: 10, columnSpan: 2 }, [
          kicker("实现亮点"),
          t("把课程设计做出“产品感”的几个关键点", {
            fontSize: 46,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(1100), height: hug }),
        ]),
        column({ width: fill, height: fill, gap: 18 }, [
          phoneShot(reportScreen, "报表页真实界面", 360),
          panel(
            {
              width: fill,
              height: fill,
              padding: 20,
              fill: COLORS.surface,
              borderRadius: 24,
            },
            image({
              path: budgetProgress,
              width: fill,
              height: fill,
              fit: "contain",
              alt: "预算进度",
            })
          ),
        ]),
        grid(
          {
            width: fill,
            height: fill,
            columns: [fr(1), fr(1)],
            rows: [fr(1), fr(1)],
            columnGap: 18,
            rowGap: 18,
          },
          [
            infoCard("共享状态管理", [
              "首页与报表页订阅同一数据源",
              "减少跨页面通信复杂度",
            ], COLORS.blue),
            infoCard("预算联动反馈", [
              "MediatorLiveData 同时监听预算与账单",
              "新增支出后自动重算进度",
            ], COLORS.orange),
            infoCard("图表展示完整", [
              "饼图看分类占比",
              "柱状图看近 7 日波动",
              "折线图看资产变化",
            ], COLORS.teal),
            infoCard("交互细节更丰富", [
              "账单支持滑动展开编辑 / 删除",
              "CSV 导出后可直接调用系统分享",
            ], COLORS.green),
          ]
        ),
        footer(),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(1), fr(1)],
        rows: [auto, fr(1), auto],
        columnGap: 34,
        rowGap: 24,
        padding: { x: 84, y: 68 },
      },
      [
        column({ width: fill, height: hug, gap: 10, columnSpan: 2 }, [
          kicker("数据设计"),
          t("当前轻量持久化方案与后续扩展路径", {
            fontSize: 46,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(1040), height: hug }),
        ]),
        column({ width: fill, height: fill, gap: 18 }, [
          panel(
            {
              width: fill,
              height: hug,
              padding: 28,
              fill: "#192334",
              borderRadius: 26,
            },
            column({ width: fill, height: hug, gap: 16 }, [
              t("当前实现：DataStore Preferences", {
                fontSize: 30,
                bold: true,
                color: COLORS.text,
              }),
              t("账户、预算和账单列表保存在本地；账单列表通过 Gson 序列化为 JSON 字符串。", {
                fontSize: 24,
                color: COLORS.textSoft,
              }, { width: fill, height: hug }),
              row({ width: fill, height: hug, gap: 12 }, [
                kicker("异步读写"),
                kicker("实现成本低", COLORS.teal),
                kicker("适合中小规模离线数据", COLORS.orange),
              ]),
            ])
          ),
          infoCard("当前链路", [
            "UI 操作 → ViewModel",
            "ViewModel → DataStoreManager",
            "DataStore → LiveData 回流界面",
          ], COLORS.blue),
          phoneShot(androidStudio, "工程实现截图", 230),
        ]),
        column({ width: fill, height: fill, gap: 18 }, [
          panel(
            {
              width: fill,
              height: hug,
              padding: 28,
              fill: COLORS.surface,
              borderRadius: 26,
            },
            column({ width: fill, height: hug, gap: 16 }, [
              t("扩展方向：关系型数据库建模", {
                fontSize: 30,
                bold: true,
                color: COLORS.text,
              }),
              t(
                "仓库额外整理了数据库表设计文档，说明项目已经为未来从轻量存储迁移到规范化数据层预留了路径。",
                { fontSize: 24, color: COLORS.textSoft },
                { width: fill, height: hug }
              ),
            ])
          ),
          grid(
            {
              width: fill,
              height: fill,
              columns: [fr(1), fr(1)],
              rows: [auto, auto, auto, auto],
              columnGap: 14,
              rowGap: 14,
            },
            [
              kicker("users", COLORS.blue),
              kicker("ledger_entries", COLORS.orange),
              kicker("categories", COLORS.teal),
              kicker("budgets", COLORS.green),
              kicker("daily_stats", COLORS.blueSoft),
              kicker("monthly_stats", COLORS.orange),
              kicker("app_settings", COLORS.teal),
              kicker("operation_logs", COLORS.red),
            ]
          ),
          infoCard("价值", [
            "当前版本足够轻便",
            "后续版本也具备工程升级空间",
          ], COLORS.teal),
        ]),
        footer(),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(0.72), fr(0.78), fr(0.9)],
        rows: [auto, fr(1), auto],
        columnGap: 24,
        rowGap: 24,
        padding: { x: 84, y: 70 },
      },
      [
        column({ width: fill, height: hug, gap: 10, columnSpan: 3 }, [
          kicker("测试结果"),
          t("系统运行稳定，核心功能闭环完整", {
            fontSize: 46,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(980), height: hug }),
          caption("论文第 5 章在实机环境下完成功能测试、性能观察和兼容性验证。"),
        ]),
        infoCard("测试环境", [
          "设备：iQOO Neo 5 SE",
          "系统：Android 14",
          "开发工具：Android Studio",
          "方法：黑盒测试",
        ], COLORS.blue),
        infoCard("重点验证模块", [
          "注册 / 登录异常输入校验",
          "账单录入判空与分类选择",
          "预算金额边界情况",
          "页面切换与数据刷新",
        ], COLORS.orange),
        panel(
          {
            width: fill,
            height: fill,
            padding: 30,
            fill: "#182534",
            borderRadius: 28,
          },
          column({ width: fill, height: fill, gap: 18, justify: "center" }, [
            t("结论", { fontSize: 28, bold: true, color: COLORS.teal }),
            t("各模块都能正确响应正常与异常输入。", {
              fontSize: 32,
              bold: true,
              color: COLORS.text,
            }, { width: fill, height: hug }),
            t("测试过程中未出现崩溃或数据异常，个别提示文案已完成优化。", {
              fontSize: 24,
              color: COLORS.textSoft,
            }, { width: fill, height: hug }),
            row({ width: fill, height: hug, gap: 12 }, [
              kicker("运行平稳", COLORS.green),
              kicker("数据准确", COLORS.teal),
              kicker("满足日常记账需求", COLORS.orange),
            ]),
          ])
        ),
        footer(),
      ]
    ),
  ])
);

slide(
  layers({ width: fill, height: fill }, [
    shape({ width: fill, height: fill, fill: COLORS.bg }),
    shape({
      width: fill,
      height: fixed(280),
      fill: "#17243C",
      borderRadius: 0,
    }),
    grid(
      {
        width: fill,
        height: fill,
        columns: [fr(1.05), fr(0.95)],
        rows: [auto, fr(1), auto],
        columnGap: 34,
        rowGap: 28,
        padding: { x: 84, y: 72 },
      },
      [
        column({ width: fill, height: hug, gap: 12, columnSpan: 2 }, [
          kicker("总结与展望"),
          t("PersonalLedger 已经具备一款完整个人账本应用的雏形", {
            fontSize: 50,
            bold: true,
            color: COLORS.text,
          }, { width: wrap(1180), height: hug }),
        ]),
        column({ width: fill, height: fill, gap: 18 }, [
          t("本次成果", { fontSize: 28, bold: true, color: COLORS.text }),
          t(
            "从需求分析、系统设计、代码实现到测试验证，项目已经完成了“能用、好讲、可扩展”的目标，适合作为课程设计或毕业设计成果展示。",
            { fontSize: 26, color: COLORS.textSoft },
            { width: wrap(760), height: hug }
          ),
          row({ width: fill, height: hug, gap: 14 }, [
            kicker("功能完整"),
            kicker("架构清晰", COLORS.teal),
            kicker("界面成熟度较高", COLORS.orange),
          ]),
          panel(
            {
              width: fill,
              height: hug,
              padding: 28,
              fill: COLORS.surface,
              borderRadius: 28,
            },
            t(
              "最值得肯定的地方，不是单个页面做得多复杂，而是项目已经形成了从记账到消费复盘的产品闭环。",
              { fontSize: 28, bold: true, color: COLORS.text },
              { width: fill, height: hug }
            )
          ),
        ]),
        column({ width: fill, height: fill, gap: 12 }, [
          t("后续可演进方向", { fontSize: 28, bold: true, color: COLORS.text }),
          infoCard("能力增强", ["云端备份与同步", "多账户管理", "账单提醒"], COLORS.blue),
          infoCard("个性化", ["自定义分类", "更丰富的主题与筛选"], COLORS.teal),
          infoCard("工程升级", ["引入 Room / SQLite", "补充测试体系"], COLORS.orange),
        ]),
        row(
          { width: fill, height: hug, columnSpan: 2, justify: "between", align: "end" },
          [
            footer(),
            t("谢谢", { fontSize: 22, color: COLORS.textSoft }),
          ]
        ),
      ]
    ),
  ])
);

for (let i = 0; i < deck.slides.items.length; i += 1) {
  const s = deck.slides.items[i];
  const canvas = new Canvas(W, H);
  const ctx = canvas.getContext("2d");
  await drawSlideToCtx(s, deck, ctx);
  await canvas.toFile(path.join(previewDir, `slide-${String(i + 1).padStart(2, "0")}.png`));
}

const pptxBlob = await PresentationFile.exportPptx(deck);
await pptxBlob.save(path.join(outDir, "PersonalLedger_项目汇报.pptx"));

const manifest = {
  slides: deck.slides.items.length,
  pptx: path.join(outDir, "PersonalLedger_项目汇报.pptx"),
  previews: previewDir,
};

await fs.writeFile(
  path.join(outDir, "manifest.json"),
  JSON.stringify(manifest, null, 2),
  "utf8"
);

console.log(JSON.stringify(manifest, null, 2));

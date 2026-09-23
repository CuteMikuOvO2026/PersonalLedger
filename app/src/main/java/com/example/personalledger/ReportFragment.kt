package com.example.personalledger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.personalledger.databinding.FragmentReportBinding
import com.google.android.material.tabs.TabLayout
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val reportViewModel: ReportViewModel by viewModels()
    private val chartTypeface: Typeface? by lazy {
        ResourcesCompat.getFont(requireContext(), R.font.app_ui_font)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** 最近 7 天的分桶（与柱状图 x 轴一一对应），点击柱子时按它查询当日明细。 */
    private var lastWeeklyDayRanges: List<DayRange> = emptyList()

    /** 饼图时间筛选选项的顺序，与 [binding.tabPieRange] 的 Tab 位置一一对应。 */
    private val pieRangeOptions = listOf(
        PieTimeRange.ALL,
        PieTimeRange.LAST_7_DAYS,
        PieTimeRange.LAST_MONTH,
        PieTimeRange.LAST_3_MONTHS
    )

    private val decimalFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            return String.format(Locale.getDefault(), "%.2f", value)
        }
    }

    private val chartColors by lazy {
        listOf(
            ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorPrimary, R.color.md_theme_primary),
            Color.parseColor("#5FC3E8"),
            ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorTertiary, R.color.md_theme_tertiary),
            Color.parseColor("#8E7CC3"),
            Color.parseColor("#E28CA8"),
            Color.parseColor("#3FB6A6"),
            Color.parseColor("#6C86E6"),
            Color.parseColor("#DBA84D")
        )
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { exportBackupTo(it) }
    }

    private val createCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let { saveCsvTo(it) }
    }

    private val createPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { generatePdfTo(it) }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBackupFrom(it) }
    }

    private val openCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importCsvFrom(it) }
    }

    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCharts()
        setupPieRangeTabs()

        reportViewModel.reportData.observe(viewLifecycleOwner) { report ->
            lastWeeklyDayRanges = report.weeklyDayRanges
            updateBarChart(report.weeklyBar)
        }

        // 饼图单独观察：切换时间范围时只刷新饼图，柱状图不受影响
        reportViewModel.pieEntries.observe(viewLifecycleOwner) { entries ->
            updatePieChart(entries)
        }

        setupMenuProvider()
    }

    /** 构建饼图的时间范围筛选 Tab，并把它与 ViewModel 的双向状态绑定。 */
    private fun setupPieRangeTabs() {
        if (binding.tabPieRange.tabCount == 0) {
            pieRangeOptions.forEach { range ->
                binding.tabPieRange.addTab(binding.tabPieRange.newTab().setText(pieRangeLabel(range)))
            }
        }

        binding.tabPieRange.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val range = pieRangeOptions.getOrNull(tab?.position ?: return) ?: return
                reportViewModel.setPieTimeRange(range)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        // 以 ViewModel 为准同步选中项（例如旋转屏幕重建后恢复用户选择）
        reportViewModel.currentPieRange.observe(viewLifecycleOwner) { range ->
            val index = pieRangeOptions.indexOf(range)
            if (index >= 0 && binding.tabPieRange.selectedTabPosition != index) {
                binding.tabPieRange.getTabAt(index)?.select()
            }
        }
    }

    private fun pieRangeLabel(range: PieTimeRange): String = getString(
        when (range) {
            PieTimeRange.ALL -> R.string.report_range_all
            PieTimeRange.LAST_7_DAYS -> R.string.report_range_7_days
            PieTimeRange.LAST_MONTH -> R.string.report_range_1_month
            PieTimeRange.LAST_3_MONTHS -> R.string.report_range_3_months
        }
    )

    override fun onResume() {
        super.onResume()
        // 应用可能在后台跨过零点，回到前台时刷新「今天」锚点，
        // 让饼图（近 N 天）与柱状图（最近 7 天）的窗口一起前移
        reportViewModel.refreshDayAnchor()
        requireActivity().invalidateOptionsMenu()
    }

    fun showResetConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                viewModel.resetAllData()
                Toast.makeText(requireContext(), getString(R.string.reset_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun exportData() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        createDocumentLauncher.launch(getString(R.string.backup_file_name, stamp))
    }

    fun exportCsv() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        createCsvLauncher.launch(getString(R.string.csv_file_name, stamp))
    }

    fun exportPdf() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        createPdfLauncher.launch(getString(R.string.pdf_file_name, stamp))
    }

    private fun saveCsvTo(uri: Uri) {
        viewModel.getCsvString { csv ->
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(csv.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(requireContext(), getString(R.string.export_csv_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.csv_export_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generatePdfTo(uri: Uri) {
        try {
            val pageWidth = 595
            val pageHeight = 842
            val margin = 40
            val contentWidth = pageWidth - margin * 2

            val document = PdfDocument()
            val textPaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
            }
            val titlePaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
                textSize = 22f
                isFakeBoldText = true
            }
            val bodyPaint = Paint().apply {
                color = Color.DKGRAY
                isAntiAlias = true
                textSize = 13f
            }
            val chartTitlePaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
                textSize = 16f
                isFakeBoldText = true
            }

            val summaryText = reportViewModel.reportData.value?.summary ?: ""
            val pieBitmap = binding.pieChartExpense.chartBitmap
            val barBitmap = binding.barChartWeekly.chartBitmap

            // --- Page 1: Summary + Pie Chart ---
            var pageInfo = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            var canvas: Canvas = pageInfo.canvas
            var y = margin

            canvas.drawText(getString(R.string.pdf_report_title), margin.toFloat(), y.toFloat(), titlePaint)
            y += 32

            for (line in summaryText.lines()) {
                if (line.isNotBlank()) {
                    canvas.drawText(line, margin.toFloat(), y.toFloat(), bodyPaint)
                    y += 20
                }
            }
            y += 12

            canvas.drawText(getString(R.string.pdf_title_expense_share), margin.toFloat(), y.toFloat(), chartTitlePaint)
            y += 24

            val pieWidth = 460
            val pieHeight = 460
            val pieLeft = (pageWidth - pieWidth) / 2
            if (pieBitmap.height > 0 && pieBitmap.width > 0) {
                val scaledPie = android.graphics.Bitmap.createScaledBitmap(pieBitmap, pieWidth, pieHeight, true)
                canvas.drawBitmap(scaledPie, pieLeft.toFloat(), y.toFloat(), null)
                scaledPie.recycle()
            }
            document.finishPage(pageInfo)

            // --- Page 2: Bar Chart ---
            pageInfo = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create())
            canvas = pageInfo.canvas
            y = margin

            canvas.drawText(getString(R.string.pdf_title_weekly_expense), margin.toFloat(), y.toFloat(), chartTitlePaint)
            y += 28

            val barMaxWidth = 500
            val barMaxHeight = 600
            if (barBitmap.height > 0 && barBitmap.width > 0) {
                val barScale = minOf(barMaxWidth.toFloat() / barBitmap.width, barMaxHeight.toFloat() / barBitmap.height)
                val barW = (barBitmap.width * barScale).toInt()
                val barH = (barBitmap.height * barScale).toInt()
                val barLeft = (pageWidth - barW) / 2
                val scaledBar = android.graphics.Bitmap.createScaledBitmap(barBitmap, barW, barH, true)
                canvas.drawBitmap(scaledBar, barLeft.toFloat(), y.toFloat(), null)
                scaledBar.recycle()
            }
            document.finishPage(pageInfo)

            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                document.writeTo(stream)
            }
            document.close()

            Toast.makeText(requireContext(), getString(R.string.export_pdf_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.export_pdf_failed) + ": ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportBackupTo(uri: Uri) {
        viewModel.getBackupJson { json ->
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_export_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun importData() {
        openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
    }

    private fun importBackupFrom(uri: Uri) {
        try {
            val json = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
            if (json.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(R.string.import_empty_file), Toast.LENGTH_SHORT).show()
                return
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_confirm_title)
                .setMessage(R.string.import_confirm_message)
                .setPositiveButton(R.string.import_confirm) { _, _ ->
                    viewModel.importBackup(json) { success ->
                        if (success) {
                            Toast.makeText(requireContext(), getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.backup_import_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- CSV 导入 ----------

    private fun importCsv() {
        openCsvLauncher.launch(
            arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
        )
    }

    /**
     * 读取并解析 CSV，然后进入列映射确认。
     *
     * 分两步是刻意的：解析（[CsvLedgerParser.parse]）只负责把文本切成表，
     * 「哪一列是什么」单独确认——这样既能读回自己导出的文件，也能接住别处导出的格式。
     */
    private fun importCsvFrom(uri: Uri) {
        val context = requireContext()
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            Toast.makeText(
                context,
                getString(R.string.csv_import_failed, e.message),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (text.isNullOrBlank()) {
            Toast.makeText(context, getString(R.string.import_empty_file), Toast.LENGTH_SHORT).show()
            return
        }

        val rows = CsvLedgerParser.parse(text)
        if (rows.isEmpty()) {
            Toast.makeText(context, getString(R.string.csv_import_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val firstRow = rows.first()
        val hasHeader = CsvLedgerParser.looksLikeHeader(firstRow)
        val dataRows = if (hasHeader) rows.drop(1) else rows
        if (dataRows.isEmpty()) {
            Toast.makeText(context, getString(R.string.csv_import_no_rows), Toast.LENGTH_SHORT).show()
            return
        }

        // 没有表头时用「第 N 列」作为下拉里的展示名
        val columnCount = rows.maxOf { it.size }
        val columnLabels = if (hasHeader) {
            firstRow.mapIndexed { index, cell ->
                cell.trim().ifEmpty { getString(R.string.csv_column_index, index + 1) }
            }
        } else {
            (1..columnCount).map { getString(R.string.csv_column_index, it) }
        }

        showCsvMappingDialog(
            columnLabels = columnLabels,
            dataRows = dataRows,
            suggested = if (hasHeader) CsvLedgerParser.suggestMapping(firstRow) else null
        )
    }

    /** 列映射对话框：5 个下拉分别指定时间 / 类型 / 分类 / 金额 / 备注，默认取自动识别结果。 */
    private fun showCsvMappingDialog(
        columnLabels: List<String>,
        dataRows: List<List<String>>,
        suggested: CsvColumnMapping?
    ) {
        val context = requireContext()
        val options = listOf(getString(R.string.csv_column_unused)) + columnLabels

        // 下拉第 0 项是「不使用」，因此真实列下标 = 选中位置 - 1
        fun buildSpinner(preselectIndex: Int): Spinner {
            val spinner = Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    options
                )
            }
            spinner.setSelection((preselectIndex + 1).coerceIn(0, options.lastIndex))
            return spinner
        }

        val dateSpinner = buildSpinner(suggested?.dateIndex ?: -1)
        val typeSpinner = buildSpinner(suggested?.typeIndex ?: -1)
        val categorySpinner = buildSpinner(suggested?.categoryIndex ?: -1)
        val amountSpinner = buildSpinner(suggested?.amountIndex ?: -1)
        val noteSpinner = buildSpinner(suggested?.noteIndex ?: -1)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 8, (24 * density).toInt(), 0)
            addView(formHint(getString(R.string.csv_import_mapping_hint)))
            addView(formLabel(R.string.csv_column_date))
            addView(dateSpinner)
            addView(formLabel(R.string.csv_column_type))
            addView(typeSpinner)
            addView(formLabel(R.string.csv_column_category))
            addView(categorySpinner)
            addView(formLabel(R.string.csv_column_amount))
            addView(amountSpinner)
            addView(formLabel(R.string.csv_column_note))
            addView(noteSpinner)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.csv_import_title)
            .setView(content)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val mapping = CsvColumnMapping(
                    dateIndex = dateSpinner.selectedItemPosition - 1,
                    typeIndex = typeSpinner.selectedItemPosition - 1,
                    categoryIndex = categorySpinner.selectedItemPosition - 1,
                    amountIndex = amountSpinner.selectedItemPosition - 1,
                    noteIndex = noteSpinner.selectedItemPosition - 1
                )
                if (!mapping.isUsable) {
                    Toast.makeText(context, R.string.csv_import_no_rows, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val result = CsvLedgerParser.toLedgerItems(dataRows, mapping)
                if (result.items.isEmpty()) {
                    Toast.makeText(context, R.string.csv_import_no_rows, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                confirmCsvImport(result)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmCsvImport(result: CsvImportResult) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(R.string.csv_import_confirm_title)
            .setMessage(
                getString(
                    R.string.csv_import_confirm_message,
                    result.items.size,
                    result.skipped
                )
            )
            .setPositiveButton(R.string.import_confirm) { _, _ ->
                viewModel.importCsvItems(result.items) { count ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.csv_import_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 自动本地备份 ----------

    private fun showAutoBackupDialog() {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 8, (24 * density).toInt(), 0)
        }
        content.addView(formHint(getString(R.string.auto_backup_desc, AutoBackupStore.KEEP_COUNT)))

        content.addView(SwitchMaterial(context).apply {
            text = getString(R.string.auto_backup_switch)
            isChecked = AutoBackupSettings.isEnabled(context)
            setPadding(0, (12 * density).toInt(), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                AutoBackupSettings.setEnabled(context, checked)
                AutoBackupWorker.sync(context)
            }
        })

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
            addView(Button(context).apply {
                text = getString(R.string.auto_backup_now)
                setOnClickListener { runAutoBackupNow(context) }
            })
            addView(Button(context).apply {
                text = getString(R.string.auto_backup_restore)
                setOnClickListener { showRestoreFromAutoBackupDialog() }
            })
        })

        AlertDialog.Builder(context)
            .setTitle(R.string.auto_backup)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun runAutoBackupNow(context: Context) {
        viewModel.getBackupJson { json ->
            try {
                AutoBackupStore.write(context, json)
                Toast.makeText(context, getString(R.string.auto_backup_done), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    getString(R.string.auto_backup_failed, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showRestoreFromAutoBackupDialog() {
        val context = requireContext()
        val files = AutoBackupStore.list(context)
        if (files.isEmpty()) {
            Toast.makeText(context, R.string.auto_backup_none, Toast.LENGTH_SHORT).show()
            return
        }

        val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val labels = files.map { stampFormat.format(Date(it.lastModified())) }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.auto_backup_restore)
            .setItems(labels) { _, which ->
                val file = files.getOrNull(which) ?: return@setItems
                confirmRestoreFromFile(context, file)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRestoreFromFile(context: Context, file: java.io.File) {
        AlertDialog.Builder(context)
            .setTitle(R.string.auto_backup_restore)
            .setMessage(R.string.auto_backup_restore_confirm)
            .setPositiveButton(R.string.import_confirm) { _, _ ->
                val json = try {
                    file.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    null
                }
                if (json.isNullOrBlank()) {
                    Toast.makeText(context, R.string.import_empty_file, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.importBackup(json) { success ->
                    Toast.makeText(
                        context,
                        if (success) R.string.import_success else R.string.import_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 表单小标题。 */
    private fun formLabel(textRes: Int): TextView = TextView(requireContext()).apply {
        setText(textRes)
        setTextColor(
            ThemeColors.of(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.text_secondary
            )
        )
        textSize = 12f
        setPadding(0, (10 * density).toInt(), 0, 0)
    }

    /** 表单说明文字。 */
    private fun formHint(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(
            ThemeColors.of(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.text_secondary
            )
        )
        textSize = 13f
    }

    private fun initCharts() {
        initPieChart()
        initBarChart()
    }

    private fun initPieChart() {
        binding.pieChartExpense.apply {
            setBackgroundColor(Color.TRANSPARENT)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 62f
            transparentCircleRadius = 66f
            setHoleColor(ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorSurfaceContainer, R.color.surface_container))
            setCenterText(getString(R.string.report_center_expense))
            setCenterTextSize(15f)
            setCenterTextColor(ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurface, R.color.text_primary))
            setCenterTextTypeface(chartTypeface)
            setEntryLabelTextSize(11f)
            setEntryLabelColor(ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurface, R.color.text_primary))
            setEntryLabelTypeface(chartTypeface)
            setNoDataText(getString(R.string.report_empty_expense))
            setNoDataTextColor(ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary))
            legend.apply {
                isEnabled = true
                textColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary)
                typeface = chartTypeface
                form = Legend.LegendForm.CIRCLE
                formSize = 10f
                yEntrySpace = 8f
                xEntrySpace = 10f
                orientation = Legend.LegendOrientation.VERTICAL
                verticalAlignment = Legend.LegendVerticalAlignment.CENTER
                horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            }
            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    val category = (e as? PieEntry)?.label ?: return
                    showCategoryRecords(category)
                }

                override fun onNothingSelected() = Unit
            })
            animateY(800)
        }
    }

    private fun initBarChart() {
        binding.barChartWeekly.apply {
            setBackgroundColor(Color.TRANSPARENT)
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setPinchZoom(false)
            setScaleEnabled(false)
            setDoubleTapToZoomEnabled(false)
            setFitBars(true)
            extraTopOffset = 8f
            extraBottomOffset = 8f
            setNoDataText(getString(R.string.report_empty_weekly))
            setNoDataTextColor(ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary)
                granularity = 1f
                typeface = chartTypeface
                axisLineColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOutlineVariant, R.color.md_theme_outlineVariant)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOutlineVariant, R.color.md_theme_outlineVariant)
                textColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary)
                axisMinimum = 0f
                typeface = chartTypeface
                axisLineColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOutlineVariant, R.color.md_theme_outlineVariant)
            }

            axisRight.isEnabled = false
            legend.isEnabled = false

            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    val index = h?.x?.toInt() ?: return
                    val barEntry = e as? BarEntry ?: return
                    // 只在当天确实有支出（柱高 > 0）时才响应，避免点击空白/无数据的天也弹窗
                    if (barEntry.y <= 0f) {
                        binding.barChartWeekly.highlightValue(null)
                        return
                    }
                    val dayRange = lastWeeklyDayRanges.getOrNull(index) ?: return
                    showDayRecords(dayRange)
                }

                override fun onNothingSelected() = Unit
            })
            animateY(800)
        }
    }

    /**
     * 点击柱状图的某一天：按需查询当天的支出记录。
     *
     * 明细不再从内存里的整表记录里筛，而是按 [DayRange] 的左闭右开区间直接查数据库，
     * 且数据库已按时间倒序返回，这里只做展示格式转换。
     */
    private fun showDayRecords(dayRange: DayRange) {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = reportViewModel.loadDayRecords(dayRange)
            if (!isAdded) return@launch

            if (items.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle(dayRange.dateKey)
                    .setMessage(getString(R.string.report_day_no_records))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val rows = items.joinToString("\n") { item ->
                val time = LedgerItemMappers.formatMillis(item.timeMillis).substringAfter(' ')
                val sign = if (item.isExpense) "-" else "+"
                "${item.categoryName}　$sign${getString(R.string.currency_symbol)}${item.amount}　" +
                    "${item.note.ifEmpty { "-" }}　$time"
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.report_day_records_title, dayRange.dateKey))
                .setMessage(rows)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /**
     * 点击饼图的某个分类：按需查询该分类在「当前所选时间范围」内的支出记录。
     *
     * 时间范围由 [ReportViewModel.loadCategoryRecords] 用与饼图同一份状态计算，
     * 保证「点进去看到的明细」与「饼图上的数字」口径一致。
     */
    private fun showCategoryRecords(categoryName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = reportViewModel.loadCategoryRecords(categoryName)
            if (!isAdded) return@launch

            if (items.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle(categoryName)
                    .setMessage(getString(R.string.report_category_no_records))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            // 分类详情跨多天，带上完整日期与时间
            val rows = items.joinToString("\n") { item ->
                val time = LedgerItemMappers.formatMillis(item.timeMillis)
                val sign = if (item.isExpense) "-" else "+"
                "$sign${getString(R.string.currency_symbol)}${item.amount}　" +
                    "${item.note.ifEmpty { "-" }}　$time"
            }

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.report_category_records_title, categoryName))
                .setMessage(rows)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun updatePieChart(entries: List<PieEntry>) {
        if (entries.isEmpty()) {
            // 非「全部」范围下空数据时给出更准确的提示，避免用户误以为账本没有支出
            val range = reportViewModel.currentPieRange.value ?: PieTimeRange.ALL
            binding.pieChartExpense.setNoDataText(
                getString(
                    if (range == PieTimeRange.ALL) R.string.report_empty_expense
                    else R.string.report_empty_expense_range
                )
            )
            binding.pieChartExpense.clear()
            binding.pieChartExpense.invalidate()
            return
        }

        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors
            sliceSpace = 3f
            valueTextSize = 11f
            valueTextColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurface, R.color.text_primary)
            valueTypeface = chartTypeface
            valueFormatter = decimalFormatter
        }

        binding.pieChartExpense.data = PieData(dataSet).apply {
            setDrawValues(true)
        }
        binding.pieChartExpense.invalidate()
    }

    private fun updateBarChart(weeklyBar: Pair<List<BarEntry>, List<String>>) {
        val (entries, labels) = weeklyBar
        if (entries.isEmpty()) {
            binding.barChartWeekly.clear()
            binding.barChartWeekly.invalidate()
            return
        }

        val dataSet = BarDataSet(entries, "").apply {
            color = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorPrimary, R.color.md_theme_primary)
            valueTextColor = ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurface, R.color.text_primary)
            valueTextSize = 10f
            valueTypeface = chartTypeface
            valueFormatter = decimalFormatter
        }

        binding.barChartWeekly.data = BarData(dataSet).apply {
            barWidth = 0.56f
        }
        binding.barChartWeekly.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.barChartWeekly.invalidate()
    }

    private fun setupMenuProvider() {
        val menuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.report_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export -> {
                        exportData()
                        true
                    }

                    R.id.action_export_csv -> {
                        exportCsv()
                        true
                    }

                    R.id.action_export_pdf -> {
                        exportPdf()
                        true
                    }

                    R.id.action_import -> {
                        importData()
                        true
                    }

                    R.id.action_import_csv -> {
                        importCsv()
                        true
                    }

                    R.id.action_auto_backup -> {
                        showAutoBackupDialog()
                        true
                    }

                    R.id.action_reset -> {
                        showResetConfirmationDialog()
                        true
                    }

                    else -> false
                }
            }
        }

        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.example.personalledger

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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import com.example.personalledger.databinding.FragmentReportBinding
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

    /** 最近 7 天的完整日期（与柱状图 x 轴一一对应），用于点击某天查看当日记录。 */
    private var lastWeeklyDates: List<String> = emptyList()

    private val decimalFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            return String.format(Locale.getDefault(), "%.2f", value)
        }
    }

    private val chartColors by lazy {
        listOf(
            ContextCompat.getColor(requireContext(), R.color.md_theme_primary),
            Color.parseColor("#5FC3E8"),
            ContextCompat.getColor(requireContext(), R.color.md_theme_tertiary),
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

        reportViewModel.reportData.observe(viewLifecycleOwner) { report ->
            lastWeeklyDates = report.weeklyDates
            updatePieChart(report.pieEntries)
            updateBarChart(report.weeklyBar)
        }

        // 观察完整历史列表，使其 value 可用，供“点击某天查看当日记录”查询
        viewModel.historyList.observe(viewLifecycleOwner) { }

        setupMenuProvider()
    }

    override fun onResume() {
        super.onResume()
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
            setHoleColor(ContextCompat.getColor(requireContext(), R.color.surface_container))
            setCenterText(getString(R.string.report_center_expense))
            setCenterTextSize(15f)
            setCenterTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setCenterTextTypeface(chartTypeface)
            setEntryLabelTextSize(11f)
            setEntryLabelColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setEntryLabelTypeface(chartTypeface)
            setNoDataText(getString(R.string.report_empty_expense))
            setNoDataTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            legend.apply {
                isEnabled = true
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                typeface = chartTypeface
                form = Legend.LegendForm.CIRCLE
                formSize = 10f
                yEntrySpace = 8f
                xEntrySpace = 10f
                orientation = Legend.LegendOrientation.VERTICAL
                verticalAlignment = Legend.LegendVerticalAlignment.CENTER
                horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            }
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
            setNoDataTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                granularity = 1f
                typeface = chartTypeface
                axisLineColor = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                axisMinimum = 0f
                typeface = chartTypeface
                axisLineColor = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
            }

            axisRight.isEnabled = false
            legend.isEnabled = false

            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    val index = h?.x?.toInt() ?: return
                    val date = lastWeeklyDates.getOrNull(index) ?: return
                    showDayRecords(date)
                }

                override fun onNothingSelected() = Unit
            })
            animateY(800)
        }
    }

    /** 点击柱状图的某一天：显示当天的支出记录。 */
    private fun showDayRecords(date: String) {
        val items = viewModel.historyList.value
            ?.filter { it.isExpense && LedgerItemMappers.formatMillis(it.timeMillis).startsWith(date) }
            ?: emptyList()

        if (items.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(date)
                .setMessage(getString(R.string.report_day_no_records))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // 按时间倒序展示
        val sorted = items.sortedByDescending { it.timeMillis }
        val rows = sorted.map { item ->
            val time = LedgerItemMappers.formatMillis(item.timeMillis).substringAfter(' ')
            val sign = if (item.isExpense) "-" else "+"
            "${item.categoryName}　$sign${getString(R.string.currency_symbol)}${item.amount}　${item.note.ifEmpty { "-" }}　$time"
        }.joinToString("\n")

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.report_day_records_title, date))
            .setMessage(rows)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updatePieChart(entries: List<PieEntry>) {
        if (entries.isEmpty()) {
            binding.pieChartExpense.clear()
            binding.pieChartExpense.invalidate()
            return
        }

        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors
            sliceSpace = 3f
            valueTextSize = 11f
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
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
            color = ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
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

package com.example.personalledger

import android.graphics.Color
import android.graphics.Typeface
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
import androidx.lifecycle.Lifecycle
import com.example.personalledger.databinding.FragmentReportBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val chartTypeface: Typeface? by lazy {
        ResourcesCompat.getFont(requireContext(), R.font.app_ui_font)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val decimalFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            return String.format(Locale.getDefault(), "%.2f", value)
        }
    }

    private val chartColors by lazy {
        listOf(
            ContextCompat.getColor(requireContext(), R.color.md_theme_primary),
            Color.parseColor("#7B8FD8"),
            ContextCompat.getColor(requireContext(), R.color.md_theme_tertiary),
            Color.parseColor("#6D9BF0"),
            Color.parseColor("#8A7BF0"),
            Color.parseColor("#5E97C9"),
            Color.parseColor("#90C7B8"),
            Color.parseColor("#A0AED0")
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

        viewModel.amount.observe(viewLifecycleOwner) { updateCharts() }
        viewModel.historyList.observe(viewLifecycleOwner) { updateCharts() }

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
        val fileName = "轻账备份_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }.json"
        createDocumentLauncher.launch(fileName)
    }

    fun exportCsv() {
        val fileName = "轻账记录_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }.csv"
        createCsvLauncher.launch(fileName)
    }

    private fun saveCsvTo(uri: Uri) {
        try {
            val csv = viewModel.getCsvString()
            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(csv.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), getString(R.string.export_csv_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "CSV导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initCharts() {
        initPieChart()
        initBarChart()
        initLineChart()
        updateCharts()
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
            animateY(800)
        }
    }

    private fun initLineChart() {
        binding.lineChartBalance.apply {
            setBackgroundColor(Color.TRANSPARENT)
            description.isEnabled = false
            setDrawGridBackground(false)
            setPinchZoom(false)
            setScaleEnabled(false)
            setDoubleTapToZoomEnabled(false)
            extraTopOffset = 8f
            extraBottomOffset = 8f
            setNoDataText(getString(R.string.report_empty_balance))
            setNoDataTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                typeface = chartTypeface
                axisLineColor = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                typeface = chartTypeface
                axisLineColor = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
            }

            axisRight.isEnabled = false
            legend.apply {
                isEnabled = true
                textColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                typeface = chartTypeface
                form = Legend.LegendForm.LINE
            }
            animateX(800)
        }
    }

    private fun updateCharts() {
        updatePieChart()
        updateBarChart()
        updateLineChart()
    }

    private fun updatePieChart() {
        val entries = viewModel.getExpenseCategoryPieEntries()
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

    private fun updateBarChart() {
        val (entries, labels) = viewModel.getWeeklyExpenseBarEntries()
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

    private fun updateLineChart() {
        val entries = viewModel.getBalanceLineEntries()
        if (entries.isEmpty()) {
            binding.lineChartBalance.clear()
            binding.lineChartBalance.invalidate()
            return
        }

        val accent = ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
        val fill = Color.parseColor("#335E7CE2")

        val dataSet = LineDataSet(entries, getString(R.string.line_balance_label)).apply {
            color = accent
            lineWidth = 2.6f
            setCircleColor(accent)
            circleRadius = 3.6f
            setDrawCircleHole(false)
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
            valueTextSize = 9f
            valueTypeface = chartTypeface
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = fill
            fillAlpha = 255
            highLightColor = ContextCompat.getColor(requireContext(), R.color.md_theme_secondary)
        }

        binding.lineChartBalance.data = LineData(dataSet)
        binding.lineChartBalance.invalidate()
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

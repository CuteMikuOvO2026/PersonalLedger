package com.example.personalledger

import android.app.Activity
import android.content.Intent
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
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.example.personalledger.databinding.FragmentReportBinding
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MainViewModel by activityViewModels()
    
    private var currentExportUri: Uri? = null
    
    /**
     * Material Design 颜色模板
     */
    private val chartColors = listOf(
        android.graphics.Color.parseColor("#E91E63"),
        android.graphics.Color.parseColor("#9C27B0"),
        android.graphics.Color.parseColor("#673AB7"),
        android.graphics.Color.parseColor("#3F51B5"),
        android.graphics.Color.parseColor("#2196F3"),
        android.graphics.Color.parseColor("#03A9F4"),
        android.graphics.Color.parseColor("#00BCD4"),
        android.graphics.Color.parseColor("#009688"),
        android.graphics.Color.parseColor("#4CAF50"),
        android.graphics.Color.parseColor("#8BC34A"),
        android.graphics.Color.parseColor("#CDDC39"),
        android.graphics.Color.parseColor("#FFEB3B"),
        android.graphics.Color.parseColor("#FFC107"),
        android.graphics.Color.parseColor("#FF9800"),
        android.graphics.Color.parseColor("#FF5722"),
        android.graphics.Color.parseColor("#795548"),
        android.graphics.Color.parseColor("#607D8B"),
        android.graphics.Color.parseColor("#F44336")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    /**
     * 文件创建启动器
     */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        uri?.let {
            currentExportUri = it
            exportToCsv(it)
        }
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
        
        // 初始化图表
        initCharts()

        // 观察总金额、预算和历史记录变化
        viewModel.amount.observe(viewLifecycleOwner) {
            updateCharts()
        }
        
        // 观察历史记录列表变化
        viewModel.historyList.observe(viewLifecycleOwner) {
            updateCharts()
        }
        
        setupMenuProvider()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().invalidateOptionsMenu()
    }

    fun showResetConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("恢复初始状态")
            .setMessage("确定要清空所有记录和预算吗？\n\n此操作不可撤销！")
            .setPositiveButton("清空") { _, _ ->
                viewModel.resetAllData()
                Toast.makeText(requireContext(), "数据已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun exportData() {
        val fileName = "个人账本_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
        createDocumentLauncher.launch(fileName)
    }

    /**
     * 导出数据到 CSV
     */
    private fun exportToCsv(uri: Uri) {
        try {
            val outputStream = requireContext().contentResolver.openOutputStream(uri)
            outputStream?.use { stream ->
                val writer = stream.bufferedWriter()
                
                // 写入表头
                writer.write("日期,类型,分类,金额,备注\n")
                
                // 写入数据
                viewModel.historyList.value?.forEach { item ->
                    val type = if (item.isExpense) "支出" else "收入"
                    val amountStr = if (item.isExpense) "-${item.amount}" else "+${item.amount}"
                    writer.write("${item.time},$type,${item.categoryName},$amountStr,${item.note}\n")
                }
                
                writer.flush()
                writer.close()
                
                Toast.makeText(requireContext(), "导出成功！", Toast.LENGTH_SHORT).show()
                showShareDialog(uri)
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示分享对话框
     */
    private fun showShareDialog(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle("导出成功")
            .setMessage("是否立即分享文件？")
            .setPositiveButton("分享") { _, _ ->
                shareFile(uri)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 分享文件
     */
    private fun shareFile(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "分享账本"))
    }

    /**
     * 初始化图表配置
     */
    private fun initCharts() {
        initPieChart()
        initBarChart()
        initLineChart()
    }

    /**
     * 初始化饼状图
     */
    private fun initPieChart() {
        binding.pieChartExpense.apply {
            description.setEnabled(false)
            setDrawHoleEnabled(true)
            setHoleRadius(40f)
            setTransparentCircleRadius(45f)
            setCenterText("支出分类")
            setCenterTextSize(14f)
            setCenterTextColor(android.graphics.Color.WHITE)
            setEntryLabelTextSize(12f)
            setEntryLabelColor(android.graphics.Color.WHITE)
            
            legend.apply {
                setEnabled(true)
                textColor = android.graphics.Color.WHITE
            }
            
            setNoDataTextColor(android.graphics.Color.WHITE)
            animateY(1000)
        }
    }

    /**
     * 初始化柱状图
     */
    private fun initBarChart() {
        binding.barChartWeekly.apply {
            description.setEnabled(false)
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setPinchZoom(false)
            setDoubleTapToZoomEnabled(false)
            animateY(1000)

            xAxis.apply {
                setPosition(XAxis.XAxisPosition.BOTTOM)
                setDrawGridLines(true)
                gridColor = android.graphics.Color.parseColor("#22FFFFFF")
                textColor = android.graphics.Color.WHITE
                setGranularity(1f)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = android.graphics.Color.parseColor("#22FFFFFF")
                textColor = android.graphics.Color.WHITE
                setAxisMinimum(0f)
            }

            axisRight.setEnabled(false)
            legend.apply {
                setEnabled(false)
                textColor = android.graphics.Color.WHITE
            }
            
            setNoDataTextColor(android.graphics.Color.WHITE)
        }
    }

    /**
     * 初始化折线图
     */
    private fun initLineChart() {
        binding.lineChartBalance.apply {
            description.setEnabled(false)
            setDrawGridBackground(false)
            setPinchZoom(false)
            setDoubleTapToZoomEnabled(false)
            animateX(1000)

            xAxis.apply {
                setPosition(XAxis.XAxisPosition.BOTTOM)
                setDrawGridLines(true)
                gridColor = android.graphics.Color.parseColor("#22FFFFFF")
                textColor = android.graphics.Color.WHITE
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = android.graphics.Color.parseColor("#22FFFFFF")
                textColor = android.graphics.Color.WHITE
            }

            axisRight.setEnabled(false)
            legend.apply {
                setEnabled(true)
                textColor = android.graphics.Color.WHITE
            }
            
            setNoDataTextColor(android.graphics.Color.WHITE)
        }
    }

    /**
     * 更新所有图表数据
     */
    private fun updateCharts() {
        updatePieChart()
        updateBarChart()
        updateLineChart()
    }

    /**
     * 更新饼状图（支出分类占比）
     */
    private fun updatePieChart() {
        val entries = viewModel.getExpenseCategoryPieEntries()

        if (entries.isNotEmpty()) {
            val dataSet = PieDataSet(entries, "").apply {
                setColors(chartColors)
                setSliceSpace(3f)
                setValueTextSize(12f)
                valueTextColor = android.graphics.Color.WHITE
            }

            val data = PieData(dataSet)
            binding.pieChartExpense.setData(data)
            binding.pieChartExpense.invalidate()
        }
    }

    /**
     * 更新柱状图（最近7天每日支出）
     */
    private fun updateBarChart() {
        val (entries, labels) = viewModel.getWeeklyExpenseBarEntries()

        if (entries.isNotEmpty()) {
            val dataSet = BarDataSet(entries, "").apply {
                setColors(chartColors)
                setValueTextSize(10f)
                valueTextColor = android.graphics.Color.WHITE
            }

            val data = BarData(dataSet)
            binding.barChartWeekly.setData(data)
            binding.barChartWeekly.xAxis.setValueFormatter(IndexAxisValueFormatter(labels))
            binding.barChartWeekly.invalidate()
        }
    }

    /**
     * 更新折线图（总资产变化趋势）
     */
    private fun updateLineChart() {
        val entries = viewModel.getBalanceLineEntries()

        if (entries.isNotEmpty()) {
            val dataSet = LineDataSet(entries, "总资产").apply {
                setColor(android.graphics.Color.parseColor("#2196F3"))
                setLineWidth(2.5f)
                setCircleRadius(4f)
                setCircleColor(android.graphics.Color.parseColor("#2196F3"))
                setDrawCircleHole(true)
                setCircleHoleRadius(2f)
                setValueTextSize(9f)
                valueTextColor = android.graphics.Color.WHITE
                setMode(LineDataSet.Mode.CUBIC_BEZIER)
                setDrawFilled(true)
                fillColor = android.graphics.Color.parseColor("#442196F3")
                fillAlpha = 128
            }

            val data = LineData(dataSet)
            binding.lineChartBalance.setData(data)
            binding.lineChartBalance.invalidate()
        }
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

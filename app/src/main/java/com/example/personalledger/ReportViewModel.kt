package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 报表页聚合数据。
 *
 * 只包含**数据库聚合**出来的图表数据与摘要文本，不含任何明细列表——
 * 明细由「点击某天 / 某分类」时按需查询（见 [ReportViewModel.loadDayRecords]）。
 */
data class ReportData(
    val weeklyBar: Pair<List<BarEntry>, List<String>>,
    /** 最近 7 天的分桶边界，与柱状图一一对应，点击柱子时按它去查明细。 */
    val weeklyDayRanges: List<DayRange>,
    val summary: String
)

/**
 * 报表页专用 ViewModel：只负责报表数据的聚合计算，
 * 与主页 / 账本操作解耦，便于各自独立测试与维护。
 *
 * 所有聚合都下推到 Room（`GROUP BY` / `SUM(CASE WHEN …)`），
 * 因此记录再多，报表页也只会传输「分类数」或「7 个分桶」量级的数据，不再随记录数线性变慢。
 */
class ReportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)

    /** 饼图时间范围筛选状态，默认显示全部时间数据。 */
    private val pieTimeRange = MutableStateFlow(PieTimeRange.ALL)

    /**
     * 「今天零点」锚点。
     *
     * 饼图（近 N 天）与柱状图（最近 7 天）都是相对「今天」的窗口，若只在构造时算一次，
     * 应用在后台跨过零点后就仍会按昨天的窗口统计。界面在 `onResume` 调用
     * [refreshDayAnchor] 重新取一次，即可让两个图表的窗口同时前移。
     */
    private val dayAnchor = MutableStateFlow(LedgerStats.startOfDay(System.currentTimeMillis()))

    /** 跨零点回到前台时刷新「今天」锚点，保证报表口径不过期。 */
    fun refreshDayAnchor() {
        dayAnchor.value = LedgerStats.startOfDay(System.currentTimeMillis())
    }

    /**
     * 报表主数据：摘要 + 柱状图，全部来自数据库聚合。
     *
     * 锚点变化时整体重算（一天最多一次），因此不需要额外的去抖处理。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reportData: LiveData<ReportData> =
        dayAnchor
            .flatMapLatest { todayStart ->
                val ranges = LedgerStats.weeklyDayRanges(todayStart)
                combine(
                    repository.reportTotals(),
                    repository.weeklyExpenseTotals(ranges),
                    repository.budgets
                ) { totals, weeklyTotals, budgetRules ->
                    ReportData(
                        weeklyBar = LedgerStats.buildWeeklyBarEntries(weeklyTotals, ranges),
                        weeklyDayRanges = ranges,
                        summary = LedgerStats.buildReportSummary(
                            totals = totals,
                            budgetValue = BudgetStats.overallLimitCents(
                                budgetRules,
                                BudgetPeriod.MONTH
                            ) / 100.0
                        )
                    )
                }
            }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .asLiveData()

    /**
     * 饼图专用数据流：随 [pieTimeRange] 实时重算，且只把「分类 → 合计」的聚合结果传上来。
     *
     * 与 [reportData] 拆分为两条流，切换时间范围时只需重算饼图，
     * 不会连带触发柱状图重建动画，从而保持原有交互体验。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pieEntries: LiveData<List<PieEntry>> =
        combine(pieTimeRange, dayAnchor) { range, todayStart -> range to todayStart }
            .distinctUntilChanged()
            .flatMapLatest { (range, todayStart) ->
                // 饼图只关心下界（近 N 天），上界不限，因此 endMillis 传 null
                repository.expenseByCategory(
                    startMillis = LedgerStats.rangeStartMillisOrNull(range, todayStart),
                    endMillis = null
                )
            }
            .map { LedgerStats.buildPieEntries(it) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .asLiveData()

    /** 当前选中的饼图时间范围。 */
    val currentPieRange: LiveData<PieTimeRange> = pieTimeRange.asLiveData()

    fun setPieTimeRange(range: PieTimeRange) {
        pieTimeRange.value = range
    }

    /** 点击柱状图的某一天：按需查询当日支出明细。 */
    suspend fun loadDayRecords(dayRange: DayRange): List<LedgerItem> =
        repository.expenseEntriesBetween(dayRange.startMillis, dayRange.endMillis)

    /**
     * 点击饼图的某个分类：按需查询该分类在**当前饼图时间范围内**的支出明细，
     * 与饼图口径保持一致（否则点进去看到的明细会对不上饼图上的数字）。
     */
    suspend fun loadCategoryRecords(categoryName: String): List<LedgerItem> =
        repository.expenseEntriesByCategory(
            categoryName = categoryName,
            startMillis = LedgerStats.rangeStartMillisOrNull(pieTimeRange.value, dayAnchor.value)
        )
}

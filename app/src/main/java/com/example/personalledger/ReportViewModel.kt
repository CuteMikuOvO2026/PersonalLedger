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

/** 报表页聚合数据 */
data class ReportData(
    val pieEntries: List<PieEntry>,
    val weeklyBar: Pair<List<BarEntry>, List<String>>,
    val weeklyDates: List<String> = emptyList(),
    val summary: String,
    val hasData: Boolean,
    /** 饼图当前时间范围下是否有支出数据，用于区分「无数据」与「该范围无记录」。 */
    val pieHasData: Boolean = pieEntries.isNotEmpty()
)

/**
 * 报表页专用 ViewModel：只负责报表数据的聚合计算，
 * 与主页 / 账本操作解耦，便于各自独立测试与维护。
 */
class ReportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)

    /** 饼图时间范围筛选状态，默认显示全部时间数据。 */
    private val pieTimeRange = MutableStateFlow(PieTimeRange.ALL)

    val reportData: LiveData<ReportData> =
        combine(repository.historyList, repository.budget) { list, budgetValue ->
            LedgerStats.buildReportData(list, budgetValue)
        }.flowOn(Dispatchers.Default).distinctUntilChanged().asLiveData()

    /**
     * 饼图专用数据流：随 [pieTimeRange] 实时重算。
     *
     * 与 [reportData] 拆分为两条流，切换时间范围时只需重算饼图，
     * 不会连带触发柱状图重建动画，从而保持原有交互体验。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pieEntries: LiveData<List<PieEntry>> =
        pieTimeRange
            .flatMapLatest { range ->
                repository.historyList.map { list ->
                    LedgerStats.getExpenseCategoryPieEntries(list, range)
                }
            }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .asLiveData()

    /** 当前选中的饼图时间范围。 */
    val currentPieRange: LiveData<PieTimeRange> = pieTimeRange.asLiveData()

    fun setPieTimeRange(range: PieTimeRange) {
        pieTimeRange.value = range
    }
}

package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** 报表页聚合数据 */
data class ReportData(
    val pieEntries: List<PieEntry>,
    val weeklyBar: Pair<List<BarEntry>, List<String>>,
    val summary: String,
    val hasData: Boolean
)

/**
 * 报表页专用 ViewModel：只负责报表数据的聚合计算，
 * 与主页 / 账本操作解耦，便于各自独立测试与维护。
 */
class ReportViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)

    val reportData: LiveData<ReportData> =
        combine(repository.historyList, repository.budget) { list, budgetValue ->
            LedgerStats.buildReportData(list, budgetValue)
        }.flowOn(Dispatchers.Default).distinctUntilChanged().asLiveData()
}

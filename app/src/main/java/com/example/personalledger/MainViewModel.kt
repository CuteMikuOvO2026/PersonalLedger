package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 记账列表筛选条件 */
sealed class LedgerFilter {
    data object All : LedgerFilter()
    data object Expense : LedgerFilter()
    data object Income : LedgerFilter()
    data class Category(val name: String) : LedgerFilter()
}

/** 筛选状态集合 */
data class LedgerFilterState(
    val filter: LedgerFilter = LedgerFilter.All,
    val search: String = "",
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null
)

/** 首页概览统计（后台计算） */
data class HomeStats(
    val todayIncome: String,
    val todayExpense: String,
    val monthExpense: String,
    val budgetProgress: Float,
    val monthIncome: String,
    val monthBalance: String,
    val monthPositive: Boolean
)

/** 报表页聚合数据（后台计算） */
data class ReportData(
    val pieEntries: List<PieEntry>,
    val weeklyBar: Pair<List<BarEntry>, List<String>>,
    val balanceEntries: List<Entry>,
    val summary: String,
    val hasData: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)

    // ---------- 原始数据 / 设置 ----------

    val historyList: LiveData<List<LedgerItem>> = repository.historyList.asLiveData()

    val budget: LiveData<Double> = repository.budget.asLiveData()

    private val defaultExpenseCategories = listOf(
        CategoryItem("餐饮", R.drawable.ic_food, "expense"),
        CategoryItem("交通", R.drawable.ic_transport, "expense"),
        CategoryItem("购物", R.drawable.ic_shopping, "expense"),
        CategoryItem("娱乐", R.drawable.ic_entertainment, "expense"),
        CategoryItem("医疗", R.drawable.ic_medical, "expense"),
        CategoryItem("教育", R.drawable.ic_education, "expense"),
        CategoryItem("住房", R.drawable.ic_housing, "expense")
    )

    private val defaultIncomeCategories = listOf(
        CategoryItem("工资", R.drawable.ic_salary, "income"),
        CategoryItem("奖金", R.drawable.ic_bonus, "income"),
        CategoryItem("投资", R.drawable.ic_investment, "income"),
        CategoryItem("兼职", R.drawable.ic_side_job, "income")
    )

    val expenseCategories: LiveData<List<CategoryItem>> =
        repository.customCategories.asLiveData().map { custom ->
            defaultExpenseCategories + custom.filter { it.type == "expense" }
        }

    val incomeCategories: LiveData<List<CategoryItem>> =
        repository.customCategories.asLiveData().map { custom ->
            defaultIncomeCategories + custom.filter { it.type == "income" }
        }

    // ---------- 首页统计（后台线程计算，避免主线程扫描、并去重） ----------

    private val homeStatsFlow =
        combine(repository.historyList, repository.budget) { list, budgetValue ->
            computeHomeStats(list, budgetValue)
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    val todayIncome: LiveData<String> = homeStatsFlow.map { it.todayIncome }.asLiveData()
    val todayExpense: LiveData<String> = homeStatsFlow.map { it.todayExpense }.asLiveData()
    val expenseThisMonth: LiveData<String> = homeStatsFlow.map { it.monthExpense }.asLiveData()
    val budgetProgress: LiveData<Float> = homeStatsFlow.map { it.budgetProgress }.asLiveData()
    val boardStats: LiveData<HomeStats> = homeStatsFlow.asLiveData()

    // ---------- 筛选状态（集中到 ViewModel，后台过滤） ----------

    private val filterState = MutableStateFlow(LedgerFilterState())

    val filteredHistory: LiveData<List<LedgerItem>> =
        combine(repository.historyList, filterState) { list, state ->
            filterLedgerList(list, state)
        }.flowOn(Dispatchers.Default).distinctUntilChanged().asLiveData()

    fun setFilter(filter: LedgerFilter) { filterState.update { it.copy(filter = filter) } }
    fun setSearchQuery(query: String) { filterState.update { it.copy(search = query) } }
    fun setAmountRange(min: Double?, max: Double?) { filterState.update { it.copy(minAmount = min, maxAmount = max) } }
    fun setDateRange(from: Long?, to: Long?) { filterState.update { it.copy(dateFrom = from, dateTo = to) } }
    fun clearFilters() { filterState.value = LedgerFilterState() }

    fun currentFilter(): LedgerFilter = filterState.value.filter
    fun currentAmountRange(): Pair<Double?, Double?> = filterState.value.minAmount to filterState.value.maxAmount
    fun currentDateRange(): Pair<Long?, Long?> = filterState.value.dateFrom to filterState.value.dateTo

    // ---------- 报表数据（后台计算） ----------

    val reportData: LiveData<ReportData> =
        combine(repository.historyList, repository.budget) { list, budgetValue ->
            buildReportData(list, budgetValue)
        }.flowOn(Dispatchers.Default).distinctUntilChanged().asLiveData()

    val importEvent = MutableLiveData<Unit>()

    // ---------- 操作 ----------

    fun addLedgerEntry(item: LedgerItem) {
        viewModelScope.launch { repository.add(item) }
    }

    fun deleteLedgerEntry(item: LedgerItem) {
        viewModelScope.launch { repository.delete(item) }
    }

    fun updateLedgerEntry(oldItem: LedgerItem, newItem: LedgerItem) {
        viewModelScope.launch { repository.update(oldItem, newItem) }
    }

    fun saveBudget(newBudget: Double) {
        viewModelScope.launch { repository.saveBudget(newBudget) }
    }

    fun addCustomCategory(name: String, iconRes: Int, type: String) {
        viewModelScope.launch { repository.addCustomCategory(name, iconRes, type) }
    }

    fun removeCustomCategory(category: CategoryItem) {
        viewModelScope.launch { repository.removeCustomCategory(category) }
    }

    fun resetAllData() {
        viewModelScope.launch { repository.resetAll() }
    }

    fun getCsvString(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.getCsvString()) }
    }

    fun getBackupJson(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(repository.getBackupJson()) }
    }

    fun importBackup(json: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = repository.restoreBackup(json)
            if (success) importEvent.postValue(Unit)
            onResult(success)
        }
    }

    // ---------- 首页统计计算（纯函数，后台执行） ----------

    private fun computeHomeStats(list: List<LedgerItem>, budgetValue: Double): HomeStats {
        val todayIncomeValue = calculateTodayIncomeFromList(list)
        val todayExpenseValue = calculateTodayExpenseFromList(list)
        val monthExpenseValue = calculateThisMonthExpenseFromList(list)
        val progressValue = if (budgetValue > 0) {
            ((monthExpenseValue / budgetValue) * 100).coerceAtMost(100.0).toFloat()
        } else 0f

        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Calendar.getInstance().time)
        var monthIncome = 0.0
        var monthExpense = 0.0
        list.forEach { item ->
            if (item.time.startsWith(monthStr)) {
                val amount = parseAmount(item.amount)
                if (item.isExpense) monthExpense += amount else monthIncome += amount
            }
        }
        val balance = monthIncome - monthExpense

        return HomeStats(
            todayIncome = formatAmount(todayIncomeValue),
            todayExpense = formatAmount(todayExpenseValue),
            monthExpense = formatAmount(monthExpenseValue),
            budgetProgress = progressValue,
            monthIncome = formatAmount(monthIncome),
            monthBalance = formatAmount(abs(balance)),
            monthPositive = balance >= 0
        )
    }

    private fun formatAmount(amount: Double): String =
        String.format(Locale.getDefault(), "%.2f", amount)

    private fun parseAmount(amountStr: String): Double = amountStr.toDoubleOrNull() ?: 0.0

    private fun calculateTodayIncomeFromList(list: List<LedgerItem>): Double {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
        var total = 0.0
        list.forEach { item ->
            if (!item.isExpense && item.time.startsWith(todayStr)) total += parseAmount(item.amount)
        }
        return total
    }

    private fun calculateTodayExpenseFromList(list: List<LedgerItem>): Double {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
        var total = 0.0
        list.forEach { item ->
            if (item.isExpense && item.time.startsWith(todayStr)) total += parseAmount(item.amount)
        }
        return total
    }

    private fun calculateThisMonthExpenseFromList(list: List<LedgerItem>): Double {
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Calendar.getInstance().time)
        var total = 0.0
        list.forEach { item ->
            if (item.isExpense && item.time.startsWith(monthStr)) total += parseAmount(item.amount)
        }
        return total
    }

    // ---------- 过滤（纯函数，后台执行） ----------

    private fun filterLedgerList(list: List<LedgerItem>, state: LedgerFilterState): List<LedgerItem> {
        val typeFiltered = when (state.filter) {
            LedgerFilter.All -> list
            LedgerFilter.Expense -> list.filter { it.isExpense }
            LedgerFilter.Income -> list.filter { !it.isExpense }
            is LedgerFilter.Category -> list.filter { it.categoryName == state.filter.name }
        }
        val textFiltered = if (state.search.isBlank()) typeFiltered
            else typeFiltered.filter { it.note.contains(state.search, ignoreCase = true) }
        val amountFiltered = if (state.minAmount == null && state.maxAmount == null) textFiltered
            else textFiltered.filter { item ->
                val amt = parseAmount(item.amount)
                (state.minAmount == null || amt >= state.minAmount) && (state.maxAmount == null || amt <= state.maxAmount)
            }
        return if (state.dateFrom == null && state.dateTo == null) amountFiltered
            else amountFiltered.filter { isItemInDateRange(it, state.dateFrom, state.dateTo) }
    }

    private fun isItemInDateRange(item: LedgerItem, dateFrom: Long?, dateTo: Long?): Boolean {
        val itemDate = parseItemDate(item.time) ?: return false
        if (dateFrom != null && itemDate < dateFrom) return false
        if (dateTo != null && itemDate > dateTo) return false
        return true
    }

    private fun parseItemDate(time: String): Long? = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val date = sdf.parse(time.take(10)) ?: return null
        val cal = Calendar.getInstance().apply {
            this.time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    } catch (_: Exception) {
        null
    }

    // ---------- 图表聚合（纯函数，后台执行） ----------

    private fun buildReportData(list: List<LedgerItem>, budgetValue: Double): ReportData {
        val pie = getExpenseCategoryPieEntries(list)
        val weekly = getWeeklyExpenseBarEntries(list)
        val balance = getBalanceLineEntries(list)
        return ReportData(
            pieEntries = pie,
            weeklyBar = weekly,
            balanceEntries = balance,
            summary = getReportSummary(list, budgetValue),
            hasData = list.isNotEmpty()
        )
    }

    private fun getExpenseCategoryPieEntries(list: List<LedgerItem>): List<PieEntry> {
        val categoryTotals = mutableMapOf<String, Float>()
        list.forEach { item ->
            if (item.isExpense) {
                val current = categoryTotals[item.categoryName] ?: 0f
                categoryTotals[item.categoryName] = current + (item.amount.toFloatOrNull() ?: 0f)
            }
        }
        return categoryTotals.map { (name, value) -> PieEntry(value, name) }
    }

    private fun getWeeklyExpenseBarEntries(list: List<LedgerItem>): Pair<List<BarEntry>, List<String>> {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        val dateRange = mutableListOf<String>()

        for (i in 0 until 7) {
            calendar.time = Date()
            calendar.add(Calendar.DAY_OF_MONTH, -i)
            dateRange.add(dayFormat.format(calendar.time))
        }
        dateRange.reverse()

        val expenseList = list.filter { it.isExpense }
        val groupedExpenses = expenseList.groupBy { item ->
            try {
                val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(item.time)
                if (parsed != null) dayFormat.format(parsed) else ""
            } catch (_: Exception) {
                ""
            }
        }.filterKeys { it.isNotEmpty() }

        dateRange.forEachIndexed { index, dateStr ->
            val dayTotal = groupedExpenses[dateStr]?.sumOf { parseAmount(it.amount) } ?: 0.0
            entries.add(BarEntry(index.toFloat(), dayTotal.toFloat()))
            try {
                val date = dayFormat.parse(dateStr)
                labels.add(if (date != null) dateFormat.format(date) else dateStr.substring(5))
            } catch (_: Exception) {
                labels.add(dateStr.substring(5))
            }
        }
        return Pair(entries, labels)
    }

    private fun getBalanceLineEntries(list: List<LedgerItem>): List<Entry> {
        val sortedList = list.sortedBy { item ->
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(item.time)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
        var balance = 0.0
        val entries = mutableListOf<Entry>()
        sortedList.forEachIndexed { index, item ->
            val amountValue = parseAmount(item.amount)
            balance += if (item.isExpense) -amountValue else amountValue
            entries.add(Entry(index.toFloat(), balance.toFloat()))
        }
        return entries
    }

    private fun getReportSummary(list: List<LedgerItem>, budgetValue: Double): String {
        var totalIncome = 0.0
        var totalExpense = 0.0
        list.forEach { item ->
            val amount = parseAmount(item.amount)
            if (item.isExpense) totalExpense += amount else totalIncome += amount
        }
        val balance = totalIncome - totalExpense
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("报表生成时间: ${df.format(Date())}")
            appendLine("总收入: ¥${formatAmount(totalIncome)}")
            appendLine("总支出: ¥${formatAmount(totalExpense)}")
            appendLine("结余: ¥${formatAmount(balance)}")
            appendLine("当月预算: ¥${formatAmount(budgetValue)}")
            appendLine("记录总数: ${list.size}")
        }
    }
}

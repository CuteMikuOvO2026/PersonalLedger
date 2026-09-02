package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

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

    // ---------- 自动记账开关 ----------

    val autoBookkeepingEnabled: LiveData<Boolean> = repository.autoBookkeepingEnabled.asLiveData()

    fun setAutoBookkeepingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoBookkeepingEnabled(enabled) }
    }

    // ---------- 首页统计（后台线程计算，避免主线程扫描、并去重） ----------

    private val homeStatsFlow =
        combine(repository.historyList, repository.budget) { list, budgetValue ->
            LedgerStats.computeHomeStats(list, budgetValue)
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    val todayIncome: LiveData<String> = homeStatsFlow.map { it.todayIncome }.asLiveData()
    val todayExpense: LiveData<String> = homeStatsFlow.map { it.todayExpense }.asLiveData()
    val expenseThisMonth: LiveData<String> = homeStatsFlow.map { it.monthExpense }.asLiveData()
    val budgetProgress: LiveData<Float> = homeStatsFlow.map { it.budgetProgress }.asLiveData()
    val boardStats: LiveData<HomeStats> = homeStatsFlow.asLiveData()

    // ---------- 筛选状态（集中到 ViewModel，筛选下推到数据库） ----------

    private val filterState = MutableStateFlow(LedgerFilterState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredHistory: LiveData<List<LedgerItem>> =
        filterState.flatMapLatest { state ->
            val typeAll = state.filter == LedgerFilter.All
            val typeExpense = state.filter == LedgerFilter.Expense
            val typeIncome = state.filter == LedgerFilter.Income
            val category = (state.filter as? LedgerFilter.Category)?.name
            repository.queryFiltered(
                typeAll = typeAll,
                typeExpense = typeExpense,
                typeIncome = typeIncome,
                category = category,
                search = state.search,
                minCents = state.minAmount?.let { (it * 100).roundToLong() },
                maxCents = state.maxAmount?.let { (it * 100).roundToLong() },
                dateFrom = state.dateFrom,
                dateTo = state.dateTo
            )
        }.flowOn(Dispatchers.Default).distinctUntilChanged().asLiveData()

    fun setFilter(filter: LedgerFilter) { filterState.update { it.copy(filter = filter) } }
    fun setSearchQuery(query: String) { filterState.update { it.copy(search = query) } }
    fun setAmountRange(min: Double?, max: Double?) { filterState.update { it.copy(minAmount = min, maxAmount = max) } }
    fun setDateRange(from: Long?, to: Long?) { filterState.update { it.copy(dateFrom = from, dateTo = to) } }
    fun clearFilters() { filterState.value = LedgerFilterState() }

    fun currentFilter(): LedgerFilter = filterState.value.filter
    fun currentAmountRange(): Pair<Double?, Double?> = filterState.value.minAmount to filterState.value.maxAmount
    fun currentDateRange(): Pair<Long?, Long?> = filterState.value.dateFrom to filterState.value.dateTo

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

}

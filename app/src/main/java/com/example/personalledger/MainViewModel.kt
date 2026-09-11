package com.example.personalledger

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val dateTo: Long? = null,
    /** 当前页码（从 1 开始）；筛选/搜索条件变化时会重置回第 1 页。 */
    val page: Int = 1
) {
    /** 把界面筛选状态转换为下推到 Room 的查询条件（不含页码，分页与总数统计共用）。 */
    fun toQuery(): LedgerQuery = LedgerQuery(
        typeAll = filter == LedgerFilter.All,
        typeExpense = filter == LedgerFilter.Expense,
        typeIncome = filter == LedgerFilter.Income,
        category = (filter as? LedgerFilter.Category)?.name,
        search = search,
        minCents = minAmount?.let { (it * 100).roundToLong() },
        maxCents = maxAmount?.let { (it * 100).roundToLong() },
        dateFrom = dateFrom,
        dateTo = dateTo
    )
}

/** 首页记录列表下推到数据库执行的筛选条件（不含分页参数）。 */
data class LedgerQuery(
    val typeAll: Boolean,
    val typeExpense: Boolean,
    val typeIncome: Boolean,
    val category: String?,
    val search: String,
    val minCents: Long?,
    val maxCents: Long?,
    val dateFrom: Long?,
    val dateTo: Long?
)

/** 首页记录列表的分页信息（驱动底部页码栏）。 */
data class LedgerPageInfo(
    val page: Int,
    val pageCount: Int,
    val totalCount: Int
) {
    val hasPrevious: Boolean get() = page > 1
    val hasNext: Boolean get() = page < pageCount
}

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

    /** 全量记录：仅报表页（图表与当日/分类明细）需要，首页列表已改为按页读取。 */
    val historyList: LiveData<List<LedgerItem>> = repository.historyList.asLiveData()

    /** 首页筛选弹窗的分类候选（数据库 DISTINCT 得出，无需读取整表）。 */
    val categoryNames: LiveData<List<String>> = repository.categoryNames.asLiveData()

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

    val themeMode: LiveData<String> = repository.themeMode.asLiveData()

    init {
        // 让账目列表能按用户所选颜色解析自定义分类
        viewModelScope.launch {
            repository.customCategories.collect { CategoryColors.registerCustomColors(it) }
        }
    }

    fun setThemeMode(mode: String) {
        // 同步写入 SharedPreferences（下次启动即时应用），再写入 DataStore（供 LiveData/备份）
        ThemeSettings.saveMode(getApplication(), mode)
        viewModelScope.launch { repository.saveThemeMode(mode) }
        AppCompatDelegate.setDefaultNightMode(ThemeSettings.toNightMode(mode))
    }

    fun setAutoBookkeepingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoBookkeepingEnabled(enabled) }
    }

    // ---------- 首页统计（数据库聚合，避免把整表读进内存） ----------

    /** “今日 / 本月”区间；跨零点回到前台时通过 [refreshHomeStatsRanges] 更新。 */
    private val statsRanges = MutableStateFlow(LedgerStats.currentStatsRanges())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val homeStatsFlow =
        statsRanges
            .flatMapLatest { ranges ->
                combine(
                    repository.homeTotals(
                        todayStart = ranges.todayStart,
                        todayEnd = ranges.todayEnd,
                        monthStart = ranges.monthStart,
                        monthEnd = ranges.monthEnd
                    ),
                    repository.budget
                ) { totals, budgetValue ->
                    LedgerStats.buildHomeStats(
                        todayIncomeCents = totals.todayIncomeCents,
                        todayExpenseCents = totals.todayExpenseCents,
                        monthIncomeCents = totals.monthIncomeCents,
                        monthExpenseCents = totals.monthExpenseCents,
                        budgetValue = budgetValue
                    )
                }
            }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    val todayIncome: LiveData<String> = homeStatsFlow.map { it.todayIncome }.asLiveData()
    val todayExpense: LiveData<String> = homeStatsFlow.map { it.todayExpense }.asLiveData()
    val expenseThisMonth: LiveData<String> = homeStatsFlow.map { it.monthExpense }.asLiveData()
    val budgetProgress: LiveData<Float> = homeStatsFlow.map { it.budgetProgress }.asLiveData()
    val boardStats: LiveData<HomeStats> = homeStatsFlow.asLiveData()

    /** 重新计算“今日 / 本月”区间（例如跨零点后回到前台），保证统计口径不过期。 */
    fun refreshHomeStatsRanges() {
        statsRanges.value = LedgerStats.currentStatsRanges()
    }

    // ---------- 筛选状态 + 分页（筛选与分页都下推到数据库） ----------

    private val filterState = MutableStateFlow(LedgerFilterState())

    /** 当前筛选条件下的总条数；分页信息与页码收敛共用同一次查询。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filteredCount: StateFlow<Int> =
        filterState
            .map { it.toQuery() }
            .distinctUntilChanged()
            .flatMapLatest { query -> repository.countFiltered(query) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        // 删除 / 导入导致记录变少时，把页码收敛回有效范围（例如删掉最后一页的最后一条）
        viewModelScope.launch {
            filteredCount.collect { total ->
                val lastPage = LedgerPaging.pageCount(total)
                filterState.update { state ->
                    if (state.page > lastPage) state.copy(page = lastPage) else state
                }
            }
        }
    }

    /** 当前页的记录（每页最多 [LedgerPaging.PAGE_SIZE] 条）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredHistory: LiveData<List<LedgerItem>> =
        filterState.flatMapLatest { state ->
            repository.queryFilteredPage(
                query = state.toQuery(),
                limit = LedgerPaging.PAGE_SIZE,
                offset = LedgerPaging.offsetOf(state.page)
            )
        }.flowOn(Dispatchers.Default).distinctUntilChanged().asLiveData()

    /** 分页信息（当前页 / 总页数 / 总条数），供首页页码栏展示。 */
    val pageInfo: LiveData<LedgerPageInfo> =
        combine(
            filteredCount,
            filterState.map { it.page }.distinctUntilChanged()
        ) { total, page ->
            val pageCount = LedgerPaging.pageCount(total)
            LedgerPageInfo(
                page = page.coerceAtMost(pageCount),
                pageCount = pageCount,
                totalCount = total
            )
        }.asLiveData()

    fun setFilter(filter: LedgerFilter) { filterState.update { it.copy(filter = filter, page = 1) } }
    fun setSearchQuery(query: String) { filterState.update { it.copy(search = query, page = 1) } }
    fun setAmountRange(min: Double?, max: Double?) { filterState.update { it.copy(minAmount = min, maxAmount = max, page = 1) } }
    fun setDateRange(from: Long?, to: Long?) { filterState.update { it.copy(dateFrom = from, dateTo = to, page = 1) } }
    fun clearFilters() { filterState.value = LedgerFilterState() }

    fun currentFilter(): LedgerFilter = filterState.value.filter
    fun currentAmountRange(): Pair<Double?, Double?> = filterState.value.minAmount to filterState.value.maxAmount
    fun currentDateRange(): Pair<Long?, Long?> = filterState.value.dateFrom to filterState.value.dateTo

    fun currentPage(): Int = filterState.value.page

    fun goToPreviousPage() = goToPage(currentPage() - 1)

    fun goToNextPage() = goToPage(currentPage() + 1)

    /** 跳转到指定页，页码会自动收敛到有效范围。 */
    fun goToPage(page: Int) {
        val target = LedgerPaging.clampPage(page, filteredCount.value)
        filterState.update { it.copy(page = target) }
    }

    val importEvent = MutableLiveData<Unit>()

    // ---------- 操作 ----------

    fun addLedgerEntry(item: LedgerItem) {
        viewModelScope.launch { repository.add(item) }
        // 新记录按时间倒序落在第 1 页，回到第 1 页让用户立刻看到刚记的这一笔
        filterState.update { it.copy(page = 1) }
    }

    /** 撤销删除：把记录放回它原本的时间位置，因此保持当前页码不变。 */
    fun restoreLedgerEntry(item: LedgerItem) {
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

    fun addCustomCategory(name: String, iconRes: Int, type: String, color: Int) {
        viewModelScope.launch { repository.addCustomCategory(name, iconRes, type, color) }
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

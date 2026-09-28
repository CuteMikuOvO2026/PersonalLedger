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
import kotlinx.coroutines.flow.Flow
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

    /**
     * 首页筛选弹窗的分类候选（数据库 DISTINCT 得出，无需读取整表）。
     *
     * 注意：这里不再暴露「整表记录」的 LiveData。首页列表按页读取、首页统计由数据库聚合、
     * 报表页也已全部下推到 SQL，任何界面都不需要把整表读进内存。
     */
    val categoryNames: LiveData<List<String>> = repository.categoryNames.asLiveData()

    /** 全部预算规则（总预算 / 分类预算，月 / 周周期）。 */
    val budgets: LiveData<List<BudgetRule>> = repository.budgets.asLiveData()

    /** 月度「不限分类」总预算（分）；未设置时为 0。首页卡片与报表摘要共用。 */
    private val monthlyOverallBudgetCents: Flow<Long> =
        repository.budgets.map { BudgetStats.overallLimitCents(it, BudgetPeriod.MONTH) }

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

    /** 预算可选的分类范围：所有支出分类（内置 + 自定义）。 */
    val budgetCategories: LiveData<List<String>> =
        expenseCategories.map { categories -> categories.map { it.name } }

    // ---------- 自动记账开关 ----------

    val autoBookkeepingEnabled: LiveData<Boolean> = repository.autoBookkeepingEnabled.asLiveData()

    /**
     * 当前主题模式，供「外观」弹窗回显。
     *
     * 刻意用 StateFlow 而不是 `Flow.asLiveData()`：后者只有在**有活跃观察者**时才开始收集，
     * 而「外观」弹窗是按需打开、直接读一次 `.value` 的，没有任何常驻观察者，
     * 于是 `.value` 永远是 null、回显永远退回「跟随系统」——这正是本 bug 的成因。
     * StateFlow 的值与有没有人订阅无关，随时可读，且初值直接取自 [ThemeSettings] 的同步读取。
     */
    private val themeModeState = MutableStateFlow(ThemeSettings.savedMode(getApplication()))

    /** 对外只暴露只读的 StateFlow；写入统一走 [setThemeMode]。 */
    val themeMode: StateFlow<String> = themeModeState

    init {
        // 让账目列表能按用户所选颜色解析自定义分类
        viewModelScope.launch {
            repository.customCategories.collect { CategoryColors.registerCustomColors(it) }
        }
    }

    fun setThemeMode(mode: String) {
        val normalized = ThemeSettings.normalize(mode)
        // 1) 先同步落盘：下次冷启动时 Application.onCreate 能直接读到并应用
        ThemeSettings.saveMode(getApplication(), normalized)
        // 2) 再更新内存状态：弹窗关闭后重新打开能立刻回显用户的选择
        themeModeState.value = normalized
        // 3) 最后切换夜间模式：AppCompat 会自动重建当前 Activity，界面立即生效
        AppCompatDelegate.setDefaultNightMode(ThemeSettings.toNightMode(normalized))
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
                    monthlyOverallBudgetCents
                ) { totals, budgetCents ->
                    LedgerStats.buildHomeStats(
                        todayIncomeCents = totals.todayIncomeCents,
                        todayExpenseCents = totals.todayExpenseCents,
                        monthIncomeCents = totals.monthIncomeCents,
                        monthExpenseCents = totals.monthExpenseCents,
                        budgetValue = budgetCents / 100.0
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

    // ---------- 预算执行情况 ----------

    /** 两种周期各自的当期区间；跨零点 / 跨周后由界面调用 [refreshHomeStatsRanges] 刷新。 */
    private data class PeriodRanges(val month: PeriodRange, val week: PeriodRange)

    private val periodRanges = MutableStateFlow(currentPeriodRanges())

    private fun currentPeriodRanges() = PeriodRanges(
        month = LedgerStats.monthRange(),
        week = LedgerStats.weekRange()
    )

    /**
     * 每条预算规则在**自己周期**内的执行情况。
     *
     * 月预算用本月区间聚合、周预算用本周区间聚合，两者互不干扰；
     * 状态判定统一走 [BudgetStats]，界面配色与提醒不再各写一套阈值。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val budgetProgressList: LiveData<List<BudgetProgress>> =
        combine(periodRanges, repository.budgets) { ranges, rules -> ranges to rules }
            .flatMapLatest { (ranges, rules) ->
                combine(
                    repository.periodCategoryTotals(ranges.month),
                    repository.periodCategoryTotals(ranges.week)
                ) { monthTotals, weekTotals ->
                    rules.map { rule ->
                        val totals = if (rule.period == BudgetPeriod.MONTH) monthTotals else weekTotals
                        BudgetStats.buildProgress(rule, totals)
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .asLiveData()

    /** 重新计算“今日 / 本月”与预算周期区间（例如跨零点后回到前台），保证统计口径不过期。 */
    fun refreshHomeStatsRanges() {
        statsRanges.value = LedgerStats.currentStatsRanges()
        periodRanges.value = currentPeriodRanges()
    }

    // ---------- 首页洞察 ----------

    /**
     * 首页洞察卡数据：本月最高支出分类、日均支出、与上月环比。
     *
     * 只发两条「按分类聚合」的查询（本月 / 上月），合计、最大值、日均都在纯函数里算，
     * 不新增任何 DAO 查询。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeInsight: LiveData<HomeInsight> =
        periodRanges
            .flatMapLatest { ranges ->
                val lastMonth = LedgerStats.lastMonthRange()
                combine(
                    repository.periodCategoryTotals(ranges.month),
                    repository.periodCategoryTotals(lastMonth)
                ) { monthTotals, lastMonthTotals ->
                    HomeInsights.build(monthTotals, lastMonthTotals)
                }
            }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .asLiveData()

    fun saveBudget(rule: BudgetRule) {
        viewModelScope.launch { repository.saveBudget(rule) }
    }

    fun deleteBudget(rule: BudgetRule) {
        viewModelScope.launch { repository.deleteBudget(rule) }
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
        // 与自定义分类一起组合：列表渲染时按分类名**同步**读取 CategoryColors 的全局色表，
        // 而色表是在分类流里**异步**注册的。若不随分类变化重新发射，冷启动时只要列表
        // 先于分类加载完成，自定义分类就会一直显示成兜底灰（直到下一次数据变化才自愈）。
        // 这里刻意不做 distinctUntilChanged：重新发射同一份列表正是为了让 ViewHolder
        // 重新绑定、重新解析颜色（submitList 走的是 notifyDataSetChanged）。
        combine(filterState, repository.customCategories) { state, _ -> state }
            .flatMapLatest { state ->
                repository.queryFilteredPage(
                    query = state.toQuery(),
                    limit = LedgerPaging.PAGE_SIZE,
                    offset = LedgerPaging.offsetOf(state.page)
                )
            }
            .flowOn(Dispatchers.Default).asLiveData()

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

    fun addCustomCategory(name: String, iconRes: Int, type: String, color: Int) {
        viewModelScope.launch { repository.addCustomCategory(name, iconRes, type, color) }
    }

    fun removeCustomCategory(category: CategoryItem) {
        viewModelScope.launch { repository.removeCustomCategory(category) }
    }

    fun resetAllData() {
        viewModelScope.launch { repository.resetAll() }
    }

    /**
     * 账本里是否有任何记录。
     *
     * 导出（CSV / PDF）前先问一次：账本为空时不该生成只有表头的文件，
     * 更不该提示「导出成功」。用计数查询而不是读全表，避免为了一个布尔值把整表拉进内存。
     */
    fun hasAnyEntry(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(repository.hasAnyEntry()) }
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

    /**
     * CSV 导入：把解析好的账目**追加**到现有记录（与「还原备份」的整体覆盖不同）。
     *
     * 导入后回到第 1 页，让用户立刻看到新进来的记录。
     */
    fun importCsvItems(items: List<LedgerItem>, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            repository.addAll(items)
            filterState.update { it.copy(page = 1) }
            onResult(items.size)
        }
    }

}

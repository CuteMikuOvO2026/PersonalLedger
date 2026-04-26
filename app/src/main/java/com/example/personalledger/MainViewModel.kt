package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    // 初始化 DataStore 管理类
    private val dataStoreManager = DataStoreManager(application)

    // 将 DataStore 的 Flow 转换为 LiveData 供界面观察
    val amount: LiveData<String> = dataStoreManager.amountFlow.asLiveData().mapToFormattedString()

    // 预算设定，从 DataStore 的 Flow 转换而来
    val budget: LiveData<Int> = dataStoreManager.budgetFlow.asLiveData()

    // 历史记录列表，直接从 DataStore 的 Flow 转换而来
    val historyList: LiveData<List<LedgerItem>> = dataStoreManager.historyListFlow.asLiveData()

    // 今日收入：自动根据历史记录列表计算
    val todayIncome: LiveData<String> = historyList.map { list: List<LedgerItem> ->
        val todayIncomeVal = calculateTodayIncomeFromList(list)
        String.format(Locale.getDefault(), "%.2f", todayIncomeVal.toDouble())
    }

    // 今日支出：自动根据历史记录列表计算
    val todayExpense: LiveData<String> = historyList.map { list: List<LedgerItem> ->
        val todayExpenseVal = calculateTodayExpenseFromList(list)
        String.format(Locale.getDefault(), "%.2f", todayExpenseVal.toDouble())
    }

    // 本月支出：自动根据历史记录列表计算
    val expenseThisMonth: LiveData<String> = historyList.map { list: List<LedgerItem> ->
        val expenseThisMonthVal = calculateThisMonthExpenseFromList(list)
        String.format(Locale.getDefault(), "%.2f", expenseThisMonthVal.toDouble())
    }

    // 预算进度：同时监听历史记录和预算变化
    private val budgetProgressSource = MediatorLiveData<Float>().apply {
        value = 0f
        
        // 监听历史记录变化
        addSource(historyList) { list ->
            updateProgressFromSource(list, budget.value)
        }
        
        // 监听预算变化
        addSource(budget) { budgetVal ->
            updateProgressFromSource(historyList.value, budgetVal)
        }
    }
    
    val budgetProgress: LiveData<Float> = budgetProgressSource
    
    private fun updateProgressFromSource(list: List<LedgerItem>?, budgetVal: Int?) {
        val actualList = list ?: emptyList()
        val actualBudget = budgetVal ?: 0
        val expenseThisMonthVal = calculateThisMonthExpenseFromList(actualList)
        val progressVal = if (actualBudget > 0) {
            (expenseThisMonthVal.toFloat() / actualBudget.toFloat() * 100).coerceAtMost(100f)
        } else {
            0f
        }
        (budgetProgressSource as MutableLiveData<Float>).value = progressVal
    }

    // 支出分类列表
    val expenseCategories = listOf(
        CategoryItem("餐饮", android.R.drawable.ic_menu_myplaces, "expense"),
        CategoryItem("交通", android.R.drawable.ic_menu_compass, "expense"),
        CategoryItem("购物", android.R.drawable.ic_menu_gallery, "expense"),
        CategoryItem("娱乐", android.R.drawable.ic_menu_slideshow, "expense"),
        CategoryItem("医疗", android.R.drawable.ic_menu_info_details, "expense"),
        CategoryItem("教育", android.R.drawable.ic_menu_edit, "expense"),
        CategoryItem("住房", android.R.drawable.ic_menu_my_calendar, "expense"),
        CategoryItem("其他", android.R.drawable.ic_menu_agenda, "expense")
    )

    // 收入分类列表
    val incomeCategories = listOf(
        CategoryItem("工资", android.R.drawable.ic_menu_call, "income"),
        CategoryItem("奖金", android.R.drawable.ic_menu_sort_by_size, "income"),
        CategoryItem("投资", android.R.drawable.ic_menu_sort_alphabetically, "income"),
        CategoryItem("兼职", android.R.drawable.ic_menu_camera, "income"),
        CategoryItem("其他", android.R.drawable.ic_menu_agenda, "income")
    )

    // 初始化时更新统计数据
    init {
        updateDailyStats()
    }

    /**
     * 将 Int 转换为格式化字符串的辅助方法
     */
    private fun LiveData<Int>.mapToFormattedString(): LiveData<String> {
        val result = MutableLiveData<String>()
        observeForever {
            result.value = String.format(Locale.getDefault(), "%.2f", it.toDouble())
        }
        return result
    }

    /**
     * 解析字符串金额为 Int
     */
    private fun parseAmount(amountStr: String): Int {
        return try {
            amountStr.toDouble().toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * 计算总收入
     * @return 所有收入记录的总和
     */
    fun calculateTotalIncome(): Int {
        var totalIncome = 0
        historyList.value?.forEach { item ->
            if (!item.isExpense) {
                totalIncome += parseAmount(item.amount)
            }
        }
        return totalIncome
    }

    /**
     * 计算总支出
     * @return 所有支出记录的总和
     */
    fun calculateTotalExpense(): Int {
        var totalExpense = 0
        historyList.value?.forEach { item ->
            if (item.isExpense) {
                totalExpense += parseAmount(item.amount)
            }
        }
        return totalExpense
    }

    /**
     * 计算进度百分比（基于总支出占预算的比例）
     * @param totalExpense 总支出金额
     * @return 进度百分比（0-100）
     */
    fun calculateProgress(totalExpense: Int): Int {
        val currentBudget = budget.value ?: 5000
        if (currentBudget <= 0) return 0
        val percentage = (totalExpense.toFloat() / currentBudget.toFloat() * 100).toInt()
        return if (percentage > 100) 100 else percentage
    }

    /**
     * 保存自定义预算
     */
    fun saveBudget(newBudget: Int) {
        viewModelScope.launch {
            dataStoreManager.saveBudget(newBudget)
        }
    }

    /**
     * 添加记账条目
     * @param newItem 要添加的 LedgerItem
     */
    fun addLedgerEntry(newItem: LedgerItem) {
        // 解析金额
        val amountToAdd = parseAmount(newItem.amount)
        if (amountToAdd <= 0) return

        viewModelScope.launch {
            // 1. 获取当前总金额
            val currentTotal = dataStoreManager.amountFlow.first()
            val newTotal = if (newItem.isExpense) {
                currentTotal + amountToAdd
            } else {
                currentTotal - amountToAdd
            }

            // 2. 保存总金额
            dataStoreManager.saveAmount(newTotal)

            // 3. 获取并更新历史记录
            val oldList = dataStoreManager.historyListFlow.first()
            val newList = mutableListOf<LedgerItem>()
            newList.add(newItem)
            newList.addAll(oldList)
            dataStoreManager.saveHistoryList(newList)

            // 4. 更新每日统计
            updateDailyStatsInternal()
        }
    }

    /**
     * 删除记账条目
     * @param item 要删除的条目
     */
    fun deleteLedgerEntry(item: LedgerItem) {
        viewModelScope.launch {
            // 1. 获取当前列表
            val currentList = dataStoreManager.historyListFlow.first().toMutableList()
            
            // 确保要删除的项在列表中
            if (currentList.remove(item)) {
                // 2. 更新总金额
                val currentTotal = dataStoreManager.amountFlow.first()
                val amountToSubtract = parseAmount(item.amount)
                val newTotal = if (item.isExpense) {
                    currentTotal - amountToSubtract
                } else {
                    currentTotal + amountToSubtract
                }
                
                // 3. 保存数据
                dataStoreManager.saveAmount(newTotal)
                dataStoreManager.saveHistoryList(currentList)

                // 4. 更新每日统计
                updateDailyStatsInternal()
            }
        }
    }

    /**
     * 内部更新每日统计（在协程中调用）
     */
    private suspend fun updateDailyStatsInternal() {
        val currentList = dataStoreManager.historyListFlow.first()
        val todayIncomeVal = calculateTodayIncomeFromList(currentList)
        val todayExpenseVal = calculateTodayExpenseFromList(currentList)
        val expenseThisMonthVal = calculateThisMonthExpenseFromList(currentList)
        val currentBudget = dataStoreManager.budgetFlow.first()
        val progressVal = if (currentBudget > 0) {
            (expenseThisMonthVal.toFloat() / currentBudget.toFloat() * 100).coerceAtMost(100f)
        } else {
            0f
        }

        (todayIncome as MutableLiveData).postValue(
            String.format(Locale.getDefault(), "%.2f", todayIncomeVal.toDouble())
        )
        (todayExpense as MutableLiveData).postValue(
            String.format(Locale.getDefault(), "%.2f", todayExpenseVal.toDouble())
        )
        (expenseThisMonth as MutableLiveData).postValue(
            String.format(Locale.getDefault(), "%.2f", expenseThisMonthVal.toDouble())
        )
        (budgetProgress as MutableLiveData).postValue(progressVal)
    }

    /**
     * 更新每日统计（供外部调用）
     */
    fun updateDailyStats() {
        viewModelScope.launch {
            updateDailyStatsInternal()
        }
    }

    /**
     * 从列表计算今日收入
     */
    private fun calculateTodayIncomeFromList(list: List<LedgerItem>): Int {
        val calendar = Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        
        var total = 0
        list.forEach { item ->
            if (!item.isExpense && item.time.startsWith(todayStr)) {
                total += parseAmount(item.amount)
            }
        }
        return total
    }

    /**
     * 从列表计算今日支出
     */
    private fun calculateTodayExpenseFromList(list: List<LedgerItem>): Int {
        val calendar = Calendar.getInstance()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        
        var total = 0
        list.forEach { item ->
            if (item.isExpense && item.time.startsWith(todayStr)) {
                total += parseAmount(item.amount)
            }
        }
        return total
    }

    /**
     * 从列表计算本月支出
     */
    private fun calculateThisMonthExpenseFromList(list: List<LedgerItem>): Int {
        val calendar = Calendar.getInstance()
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
        
        var total = 0
        list.forEach { item ->
            if (item.isExpense && item.time.startsWith(monthStr)) {
                total += parseAmount(item.amount)
            }
        }
        return total
    }

    /**
     * 计算今日收入
     */
    private fun calculateTodayIncome(): Int {
        return calculateTodayIncomeFromList(historyList.value ?: emptyList())
    }

    /**
     * 计算今日支出
     */
    private fun calculateTodayExpense(): Int {
        return calculateTodayExpenseFromList(historyList.value ?: emptyList())
    }

    /**
     * 计算本月支出
     */
    private fun calculateThisMonthExpense(): Int {
        return calculateThisMonthExpenseFromList(historyList.value ?: emptyList())
    }

    /**
     * 清空所有数据
     */
    fun resetAllData() {
        viewModelScope.launch {
            dataStoreManager.clearAllData()
        }
    }

    /**
     * 计算支出分类占比，用于饼状图
     * @return PieEntry 列表
     */
    fun getExpenseCategoryPieEntries(): List<PieEntry> {
        val categoryTotals = mutableMapOf<String, Float>()
        historyList.value?.forEach { item ->
            if (item.isExpense) {
                val current = categoryTotals[item.categoryName] ?: 0f
                val amountVal = try {
                    item.amount.toFloat()
                } catch (e: NumberFormatException) {
                    0f
                }
                categoryTotals[item.categoryName] = current + amountVal
            }
        }
        return categoryTotals.map { (name, value) ->
            PieEntry(value, name)
        }
    }

    /**
     * 获取最近7天每日支出数据，用于柱状图
     * @return BarEntry 列表和对应的日期标签
     */
    fun getWeeklyExpenseBarEntries(): Pair<List<BarEntry>, List<String>> {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        
        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        
        // 获取7天日期范围（从今天起往回推6天，共7天）
        val dateRange = mutableListOf<String>()
        for (i in 0 until 7) {
            calendar.time = Date() // 重置为当前时间
            calendar.add(Calendar.DAY_OF_MONTH, -i)
            val dateStr = dayFormat.format(calendar.time)
            dateRange.add(dateStr)
        }
        dateRange.reverse() // 反转，让日期从早到晚排列
        
        // 筛选出所有支出数据
        val expenseList = historyList.value?.filter { it.isExpense } ?: emptyList()
        
        // 按日期分组支出数据
        val groupedExpenses = expenseList.groupBy { item ->
            try {
                val itemDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(item.time)
                dayFormat.format(itemDate)
            } catch (e: Exception) {
                ""
            }
        }.filter { it.key.isNotEmpty() }
        
        // 遍历7天日期范围，补全0值
        dateRange.forEachIndexed { index, dateStr ->
            val dayTotal = if (groupedExpenses.containsKey(dateStr)) {
                groupedExpenses[dateStr]?.sumOf { parseAmount(it.amount) } ?: 0
            } else {
                0
            }
            
            entries.add(BarEntry(index.toFloat(), dayTotal.toFloat()))
            
            // 格式化标签，显示日期
            try {
                val date = dayFormat.parse(dateStr)
                labels.add(dateFormat.format(date))
            } catch (e: Exception) {
                labels.add(dateStr.substring(5)) // 如果解析失败，显示月份和日期部分
            }
        }
        
        return Pair(entries, labels)
    }

    /**
     * 计算总资产变化趋势，用于折线图
     * @return Entry 列表
     */
    fun getBalanceLineEntries(): List<Entry> {
        val sortedList = historyList.value?.sortedBy { item ->
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(item.time)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        } ?: emptyList()
        
        var balance = 0
        val entries = mutableListOf<Entry>()
        
        sortedList.forEachIndexed { index, item ->
            val amountVal = parseAmount(item.amount)
            if (item.isExpense) {
                balance -= amountVal
            } else {
                balance += amountVal
            }
            entries.add(Entry(index.toFloat(), balance.toFloat()))
        }
        
        return entries
    }
}

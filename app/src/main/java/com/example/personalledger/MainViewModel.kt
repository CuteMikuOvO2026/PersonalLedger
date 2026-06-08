package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    val amount: LiveData<String> = dataStoreManager.amountFlow.asLiveData().map { formatAmount(it) }
    val budget: LiveData<Double> = dataStoreManager.budgetFlow.asLiveData()
    val historyList: LiveData<List<LedgerItem>> = dataStoreManager.historyListFlow.asLiveData()
    val importEvent = MutableLiveData<Unit>()

    val todayIncome: LiveData<String> = historyList.map { list ->
        formatAmount(calculateTodayIncomeFromList(list))
    }

    val todayExpense: LiveData<String> = historyList.map { list ->
        formatAmount(calculateTodayExpenseFromList(list))
    }

    val expenseThisMonth: LiveData<String> = historyList.map { list ->
        formatAmount(calculateThisMonthExpenseFromList(list))
    }

    private val budgetProgressSource = MediatorLiveData<Float>().apply {
        value = 0f
        addSource(historyList) { list -> updateProgressFromSource(list, budget.value) }
        addSource(budget) { budgetValue -> updateProgressFromSource(historyList.value, budgetValue) }
    }

    val budgetProgress: LiveData<Float> = budgetProgressSource

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
        dataStoreManager.customCategoriesFlow.asLiveData().map { custom ->
            defaultExpenseCategories + custom.filter { it.type == "expense" }
        }

    val incomeCategories: LiveData<List<CategoryItem>> =
        dataStoreManager.customCategoriesFlow.asLiveData().map { custom ->
            defaultIncomeCategories + custom.filter { it.type == "income" }
        }

    fun addCustomCategory(name: String, iconRes: Int, type: String) {
        viewModelScope.launch {
            val current = dataStoreManager.customCategoriesFlow.first().toMutableList()
            current.add(CategoryItem(name, iconRes, type, isCustom = true))
            dataStoreManager.saveCustomCategories(current)
        }
    }

    fun removeCustomCategory(category: CategoryItem) {
        viewModelScope.launch {
            val current = dataStoreManager.customCategoriesFlow.first().toMutableList()
            current.removeAll { it.name == category.name && it.type == category.type }
            dataStoreManager.saveCustomCategories(current)
        }
    }

    init {
        updateDailyStats()
    }

    private fun formatAmount(amount: Double): String {
        return String.format(Locale.getDefault(), "%.2f", amount)
    }

    private fun updateProgressFromSource(list: List<LedgerItem>?, budgetValue: Double?) {
        val actualList = list ?: emptyList()
        val actualBudget = budgetValue ?: 0.0
        val expenseValue = calculateThisMonthExpenseFromList(actualList)
        val progressValue = if (actualBudget > 0) {
            ((expenseValue / actualBudget) * 100).coerceAtMost(100.0).toFloat()
        } else {
            0f
        }
        budgetProgressSource.value = progressValue
    }

    private fun parseAmount(amountStr: String): Double {
        return amountStr.toDoubleOrNull() ?: 0.0
    }

    fun calculateTotalIncome(): Double {
        var totalIncome = 0.0
        historyList.value?.forEach { item ->
            if (!item.isExpense) totalIncome += parseAmount(item.amount)
        }
        return totalIncome
    }

    fun calculateTotalExpense(): Double {
        var totalExpense = 0.0
        historyList.value?.forEach { item ->
            if (item.isExpense) totalExpense += parseAmount(item.amount)
        }
        return totalExpense
    }

    fun calculateProgress(totalExpense: Double): Int {
        val currentBudget = budget.value ?: 5000.0
        if (currentBudget <= 0) return 0
        return ((totalExpense / currentBudget) * 100).toInt().coerceAtMost(100)
    }

    fun saveBudget(newBudget: Double) {
        viewModelScope.launch {
            dataStoreManager.saveBudget(newBudget)
        }
    }

    fun addLedgerEntry(newItem: LedgerItem) {
        val amountToAdd = parseAmount(newItem.amount)
        if (amountToAdd <= 0) return

        viewModelScope.launch {
            val currentTotal = dataStoreManager.amountFlow.first()
            val newTotal = if (newItem.isExpense) {
                currentTotal + amountToAdd
            } else {
                currentTotal - amountToAdd
            }

            val oldList = dataStoreManager.historyListFlow.first()
            val newList = mutableListOf(newItem).apply { addAll(oldList) }
            dataStoreManager.saveAmountAndHistory(newTotal, newList)

            updateDailyStatsInternal()
        }
    }

    fun deleteLedgerEntry(item: LedgerItem) {
        viewModelScope.launch {
            val currentList = dataStoreManager.historyListFlow.first().toMutableList()
            if (currentList.removeAll { it.id == item.id }) {
                val currentTotal = dataStoreManager.amountFlow.first()
                val amountToSubtract = parseAmount(item.amount)
                val newTotal = if (item.isExpense) {
                    currentTotal - amountToSubtract
                } else {
                    currentTotal + amountToSubtract
                }

                dataStoreManager.saveAmountAndHistory(newTotal, currentList)
                updateDailyStatsInternal()
            }
        }
    }

    fun updateLedgerEntry(oldItem: LedgerItem, newItem: LedgerItem) {
        viewModelScope.launch {
            val currentList = dataStoreManager.historyListFlow.first().toMutableList()
            val index = currentList.indexOfFirst { it.id == oldItem.id }
            if (index == -1) return@launch

            currentList[index] = newItem

            val currentTotal = dataStoreManager.amountFlow.first()
            val revertedTotal = if (oldItem.isExpense) {
                currentTotal - parseAmount(oldItem.amount)
            } else {
                currentTotal + parseAmount(oldItem.amount)
            }
            val updatedTotal = if (newItem.isExpense) {
                revertedTotal + parseAmount(newItem.amount)
            } else {
                revertedTotal - parseAmount(newItem.amount)
            }

            dataStoreManager.saveAmountAndHistory(updatedTotal, currentList)
            updateDailyStatsInternal()
        }
    }

    private suspend fun updateDailyStatsInternal() {
        val currentList = dataStoreManager.historyListFlow.first()
        val todayIncomeValue = calculateTodayIncomeFromList(currentList)
        val todayExpenseValue = calculateTodayExpenseFromList(currentList)
        val expenseThisMonthValue = calculateThisMonthExpenseFromList(currentList)
        val currentBudget = dataStoreManager.budgetFlow.first()
        val progressValue = if (currentBudget > 0) {
            ((expenseThisMonthValue / currentBudget) * 100).coerceAtMost(100.0).toFloat()
        } else {
            0f
        }

        (todayIncome as MutableLiveData).postValue(formatAmount(todayIncomeValue))
        (todayExpense as MutableLiveData).postValue(formatAmount(todayExpenseValue))
        (expenseThisMonth as MutableLiveData).postValue(formatAmount(expenseThisMonthValue))
        (budgetProgress as MutableLiveData).postValue(progressValue)
    }

    fun updateDailyStats() {
        viewModelScope.launch {
            updateDailyStatsInternal()
        }
    }

    private fun calculateTodayIncomeFromList(list: List<LedgerItem>): Double {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Calendar.getInstance().time)
        var total = 0.0
        list.forEach { item ->
            if (!item.isExpense && item.time.startsWith(todayStr)) {
                total += parseAmount(item.amount)
            }
        }
        return total
    }

    private fun calculateTodayExpenseFromList(list: List<LedgerItem>): Double {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Calendar.getInstance().time)
        var total = 0.0
        list.forEach { item ->
            if (item.isExpense && item.time.startsWith(todayStr)) {
                total += parseAmount(item.amount)
            }
        }
        return total
    }

    private fun calculateThisMonthExpenseFromList(list: List<LedgerItem>): Double {
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            .format(Calendar.getInstance().time)
        var total = 0.0
        list.forEach { item ->
            if (item.isExpense && item.time.startsWith(monthStr)) {
                total += parseAmount(item.amount)
            }
        }
        return total
    }

    fun resetAllData() {
        viewModelScope.launch {
            dataStoreManager.clearAllData()
        }
    }

    fun getCsvString(): String {
        val sb = StringBuilder()
        sb.append("\uFEFF") // BOM for Excel Chinese compatibility
        sb.appendLine("时间,类型,分类,金额,备注")
        historyList.value.orEmpty().forEach { item ->
            val type = if (item.isExpense) "支出" else "收入"
            val escapedNote = item.note.replace("\"", "\"\"")
            sb.appendLine("${item.time},$type,${item.categoryName},\"${item.amount}\",\"$escapedNote\"")
        }
        return sb.toString()
    }

    fun getBackupJson(onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(dataStoreManager.getBackupJson())
        }
    }

    fun importBackup(json: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = dataStoreManager.restoreFromBackup(json)
            if (success) {
                updateDailyStatsInternal()
                importEvent.postValue(Unit)
            }
            onResult(success)
        }
    }

    fun getExpenseCategoryPieEntries(): List<PieEntry> {
        val categoryTotals = mutableMapOf<String, Float>()
        historyList.value?.forEach { item ->
            if (item.isExpense) {
                val current = categoryTotals[item.categoryName] ?: 0f
                categoryTotals[item.categoryName] = current + (item.amount.toFloatOrNull() ?: 0f)
            }
        }
        return categoryTotals.map { (name, value) -> PieEntry(value, name) }
    }

    fun getWeeklyExpenseBarEntries(): Pair<List<BarEntry>, List<String>> {
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

        val expenseList = historyList.value?.filter { it.isExpense } ?: emptyList()
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

    fun getReportSummary(): String {
        val totalIncome = calculateTotalIncome()
        val totalExpense = calculateTotalExpense()
        val balance = totalIncome - totalExpense
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("报表生成时间: ${df.format(Date())}")
            appendLine("总收入: ¥${formatAmount(totalIncome)}")
            appendLine("总支出: ¥${formatAmount(totalExpense)}")
            appendLine("结余: ¥${formatAmount(balance)}")
            appendLine("当月预算: ¥${formatAmount(budget.value ?: 0.0)}")
            appendLine("记录总数: ${historyList.value?.size ?: 0}")
        }
    }

    fun getBalanceLineEntries(): List<Entry> {
        val sortedList = historyList.value?.sortedBy { item ->
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(item.time)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        } ?: emptyList()

        var balance = 0.0
        val entries = mutableListOf<Entry>()

        sortedList.forEachIndexed { index, item ->
            val amountValue = parseAmount(item.amount)
            balance += if (item.isExpense) -amountValue else amountValue
            entries.add(Entry(index.toFloat(), balance.toFloat()))
        }

        return entries
    }
}

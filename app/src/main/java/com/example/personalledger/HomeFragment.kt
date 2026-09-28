package com.example.personalledger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.widget.SearchView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.FragmentHomeBinding
import com.google.android.material.datepicker.MaterialDatePicker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    /** 已渲染的页码，用于仅在真正翻页时把列表滚回顶部。 */
    private var renderedPage: Int? = null

    /** 筛选弹窗的分类候选（由数据库去重得出，避免为了筛选把整表读进内存）。 */
    private var filterCategoryNames: List<String> = emptyList()

    /** 预算可选的分类范围（所有支出分类）。 */
    private var budgetCategoryNames: List<String> = emptyList()

    /** 通知权限申请结果（Android 13+）：没拿到就提示用户，开关保持关闭。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val ctx = context ?: return@registerForActivityResult
        if (!granted) {
            Toast.makeText(ctx, R.string.budget_alert_need_permission, Toast.LENGTH_SHORT).show()
        }
    }

    private val ledgerAdapter: LedgerAdapter = LedgerAdapter(
        onEditClick = { item: LedgerItem ->
            ledgerAdapter.closeOpenItem()
            AddEntryBottomSheetDialogFragment.newInstance(item)
                .show(childFragmentManager, AddEntryBottomSheetDialogFragment.TAG)
        },
        onDeleteClick = { item: LedgerItem ->
            ledgerAdapter.closeOpenItem()
            showDeleteConfirmDialog(item)
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFilterClick()
        setupPagerClick()
        setupObservers()
        setupFab()
        setupBudgetClick()
        setupMenuProvider()
    }

    override fun onResume() {
        super.onResume()
        // 应用可能在后台跨过零点，回到前台时刷新“今日/本月”统计口径
        viewModel.refreshHomeStatsRanges()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ledgerAdapter
            itemAnimator = DefaultItemAnimator()
        }
    }

    private fun setupObservers() {
        viewModel.filteredHistory.observe(viewLifecycleOwner) { list ->
            ledgerAdapter.submitList(list)
        }

        viewModel.pageInfo.observe(viewLifecycleOwner) { info ->
            renderPageInfo(info)
        }

        // 分类候选由数据库 DISTINCT 得出，筛选弹窗不再依赖全量记录
        viewModel.categoryNames.observe(viewLifecycleOwner) { names ->
            filterCategoryNames = names
        }

        // 预算可选范围（所有支出分类：内置 + 自定义）
        viewModel.budgetCategories.observe(viewLifecycleOwner) { names ->
            budgetCategoryNames = names
        }

        viewModel.todayIncome.observe(viewLifecycleOwner) { income ->
            binding.textTodayIncome.text = "+$income"
        }

        viewModel.todayExpense.observe(viewLifecycleOwner) { expense ->
            binding.textTodayExpense.text = "-$expense"
        }

        viewModel.budgetProgressList.observe(viewLifecycleOwner) { progressList ->
            renderBudgetCard(progressList)
        }

        viewModel.homeInsight.observe(viewLifecycleOwner) { insight ->
            renderInsightCard(insight)
        }

        viewModel.expenseThisMonth.observe(viewLifecycleOwner) { expense ->
            binding.textExpenseThisMonth.text = getString(
                R.string.expense_this_month,
                getString(R.string.currency_symbol),
                expense
            )
        }

        viewModel.boardStats.observe(viewLifecycleOwner) { stats ->
            updateBoard(stats)
        }

        viewModel.importEvent.observe(viewLifecycleOwner) {
            viewModel.clearFilters()
            updateAmountFilterTint()
            updateDateFilterTint()
        }
    }

    /**
     * 渲染分页栏与列表/空状态。
     *
     * “是否有记录”以总条数为准，避免翻页瞬间（当前页数据尚未返回）误显示空状态。
     */
    private fun renderPageInfo(info: LedgerPageInfo) {
        val hasRecords = info.totalCount > 0
        binding.layoutPager.visibility = if (hasRecords) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (hasRecords) View.VISIBLE else View.GONE
        binding.layoutEmpty.visibility = if (hasRecords) View.GONE else View.VISIBLE

        binding.textPageIndicator.text =
            getString(R.string.page_indicator, info.page, info.pageCount, info.totalCount)
        setPageButtonEnabled(binding.buttonPrevPage, info.hasPrevious)
        setPageButtonEnabled(binding.buttonNextPage, info.hasNext)

        scrollToRecordsTopWhenPageChanged(info)
    }

    private fun setPageButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    /** 翻页后把记录区滚回可视区域顶部，否则会停留在上一页的滚动位置。 */
    private fun scrollToRecordsTopWhenPageChanged(info: LedgerPageInfo) {
        val previousPage = renderedPage
        renderedPage = info.page
        if (previousPage == null || previousPage == info.page) return
        binding.nestedScrollView.post {
            binding.nestedScrollView.smoothScrollTo(0, binding.layoutRecordsHeader.top)
        }
    }

    private fun updateBoard(stats: HomeStats) {
        val amountColor = if (stats.monthPositive) {
            binding.textBoardTitle.text = getString(R.string.home_balance_title)
            binding.textAmountSign.text = "+"
            ContextCompat.getColor(requireContext(), R.color.income)
        } else {
            binding.textBoardTitle.text = getString(R.string.home_overspend_title)
            binding.textAmountSign.text = "-"
            ContextCompat.getColor(requireContext(), R.color.expense)
        }
        binding.textAmountSign.setTextColor(amountColor)
        binding.textAmount.setTextColor(amountColor)
        binding.textAmountCurrency.setTextColor(amountColor)
        binding.textAmount.text = stats.monthBalance
    }

    private fun setupFilterClick() {
        binding.textViewAll.setOnClickListener { showFilterDialog() }
        binding.textAmountFilter.setOnClickListener { showAmountFilterDialog() }
        binding.textDateFilter.setOnClickListener { showDateFilterDialog() }
    }

    private fun setupPagerClick() {
        binding.buttonPrevPage.setOnClickListener { viewModel.goToPreviousPage() }
        binding.buttonNextPage.setOnClickListener { viewModel.goToNextPage() }
    }

    private fun buildFilterOptions(): List<String> {
        return mutableListOf("全部", "支出", "收入").apply {
            addAll(filterCategoryNames.filter { it.isNotBlank() })
        }
    }

    private fun showFilterDialog() {
        val filterOptions = buildFilterOptions()
        val checkedItem = when (val filter = viewModel.currentFilter()) {
            LedgerFilter.All -> 0
            LedgerFilter.Expense -> 1
            LedgerFilter.Income -> 2
            is LedgerFilter.Category -> filterOptions.indexOf(filter.name).takeIf { it >= 0 } ?: 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle("筛选类型")
            .setSingleChoiceItems(filterOptions.toTypedArray(), checkedItem) { dialog, which ->
                val newFilter = when (which) {
                    1 -> LedgerFilter.Expense
                    2 -> LedgerFilter.Income
                    in 3 until filterOptions.size -> LedgerFilter.Category(filterOptions[which])
                    else -> LedgerFilter.All
                }
                viewModel.setFilter(newFilter)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAmountFilterDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 32, 48, 0)
        }
        val (currentMin, currentMax) = viewModel.currentAmountRange()

        val editMin = EditText(context).apply {
            hint = getString(R.string.amount_filter_hint_min)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (currentMin != null) setText(String.format(Locale.getDefault(), "%.2f", currentMin))
        }
        val editMax = EditText(context).apply {
            hint = getString(R.string.amount_filter_hint_max)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 24
            }
            if (currentMax != null) setText(String.format(Locale.getDefault(), "%.2f", currentMax))
        }
        layout.addView(editMin)
        layout.addView(editMax)

        AlertDialog.Builder(context)
            .setTitle(R.string.amount_filter)
            .setView(layout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.setAmountRange(
                    editMin.text.toString().toDoubleOrNull(),
                    editMax.text.toString().toDoubleOrNull()
                )
                updateAmountFilterTint()
            }
            .setNeutralButton(R.string.amount_filter_clear) { _, _ ->
                viewModel.setAmountRange(null, null)
                updateAmountFilterTint()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateAmountFilterTint() {
        val (min, max) = viewModel.currentAmountRange()
        val active = min != null || max != null
        val color = if (active) {
            ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorTertiary, R.color.md_theme_tertiary)
        } else {
            ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary)
        }
        binding.textAmountFilter.setTextColor(color)
    }

    /**
     * 日期区间筛选。
     *
     * [MaterialDatePicker] 给出的是 **UTC 零点**毫秒，而 DAO 里的比较是
     * `timeMillis >= dateFrom AND timeMillis <= dateTo`（本地时间戳、闭区间）。
     * 直接透传会在东八区漏掉起始日 00:00–08:00 与结束日 08:00 之后的记录，
     * 因此这里必须经 [LedgerDateTime] 换算：下界取当日本地零点、上界取当日最后一毫秒。
     */
    private fun showDateFilterDialog() {
        val (from, to) = viewModel.currentDateRange()
        val rangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.date_filter))
            .apply {
                // 已选区间要换算回 UTC 零点再回显，否则会显示成前一天
                if (from != null && to != null) {
                    setSelection(
                        androidx.core.util.Pair(
                            LedgerDateTime.dayToPicker(from),
                            LedgerDateTime.dayToPicker(to)
                        )
                    )
                }
            }
            .build()

        rangePicker.addOnPositiveButtonClickListener { selection ->
            viewModel.setDateRange(
                LedgerDateTime.dayStartFromPicker(selection.first),
                LedgerDateTime.dayEndFromPicker(selection.second)
            )
            updateDateFilterTint()
        }

        rangePicker.show(childFragmentManager, "date_range_picker")
    }

    private fun updateDateFilterTint() {
        val (from, to) = viewModel.currentDateRange()
        val active = from != null || to != null
        val color = if (active) {
            ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorPrimary, R.color.md_theme_primary)
        } else {
            ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary)
        }
        binding.textDateFilter.setTextColor(color)
    }

    /**
     * 渲染首页预算卡片。
     *
     * 卡片主体仍是「月度总预算」的进度（与改造前一致）；
     * 若存在超支的分类预算，额外显示一行提示，点击进入预算管理。
     */
    private fun renderBudgetCard(progressList: List<BudgetProgress>) {
        val overall = progressList.firstOrNull {
            it.rule.period == BudgetPeriod.MONTH && it.rule.isOverall
        }

        if (overall == null) {
            binding.textBudgetLabel.text = getString(R.string.budget_not_set)
            binding.layoutBudget.visibility = View.GONE
        } else {
            binding.textBudgetLabel.text = getString(
                R.string.budget_amount,
                getString(R.string.currency_symbol),
                LedgerStats.formatAmount(overall.rule.limitCents)
            )
            binding.layoutBudget.visibility = View.VISIBLE
            binding.progressBudget.progress = overall.percent.coerceAtMost(100)
            binding.textBudgetPercent.text = getString(R.string.percent_value, overall.percent)
            updateProgressColor(overall.state)
        }

        val overspentCategories = progressList.count {
            !it.rule.isOverall && it.state == BudgetState.OVER
        }
        binding.textBudgetAlert.visibility =
            if (overspentCategories > 0) View.VISIBLE else View.GONE
        if (overspentCategories > 0) {
            binding.textBudgetAlert.text =
                getString(R.string.budget_category_over, overspentCategories)
        }
    }

    /** 进度条配色统一由 [BudgetState] 决定，阈值只存在于 [BudgetStats] 一处。 */
    private fun updateProgressColor(state: BudgetState) {
        val tintColor = ContextCompat.getColor(
            requireContext(),
            when (state) {
                BudgetState.OVER -> R.color.expense
                BudgetState.WARNING -> R.color.warning
                BudgetState.NORMAL -> R.color.income
            }
        )
        binding.progressBudget.progressTintList = ColorStateList.valueOf(tintColor)
        binding.progressBudget.invalidate()
    }

    /**
     * 渲染首页洞察卡：本月最高支出分类 / 日均支出 / 与上月环比。
     *
     * 没有数据时统一显示占位符，避免出现「¥0.00 最高支出」这类无意义文案；
     * 环比只有在上月确实有支出时才展示（[HomeInsight.monthOverMonthPercent] 为 null 表示无法比较）。
     */
    private fun renderInsightCard(insight: HomeInsight) {
        val placeholder = getString(R.string.insight_no_data)
        val currency = getString(R.string.currency_symbol)

        binding.textInsightTopCategory.text = insight.topCategoryName ?: placeholder
        binding.textInsightDailyAverage.text = if (insight.hasExpense) {
            currency + LedgerStats.formatAmount(insight.dailyAverageCents)
        } else {
            placeholder
        }

        val percent = insight.monthOverMonthPercent
        binding.textInsightMonthOverMonth.text = when {
            percent == null -> placeholder
            percent == 0 -> getString(R.string.insight_mom_flat)
            else -> getString(R.string.insight_mom_percent, percent)
        }
        // 花得比上月多用「支出色」提示，少用「收入色」，无法比较用次级文字色
        binding.textInsightMonthOverMonth.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    percent == null || percent == 0 -> R.color.text_secondary
                    percent > 0 -> R.color.expense
                    else -> R.color.income
                }
            )
        )
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            AddEntryBottomSheetDialogFragment()
                .show(childFragmentManager, AddEntryBottomSheetDialogFragment.TAG)
        }
    }

    private fun setupBudgetClick() {
        binding.layoutBudgetClick.setOnClickListener { showBudgetManagerDialog() }
        binding.textBudgetAlert.setOnClickListener { showBudgetManagerDialog() }
    }

    /**
     * 预算管理：列出全部预算规则及其当期执行情况，可新增或删除。
     *
     * 列表项文本包含「当期已花 / 限额（百分比）+ 状态」，因此各分类的超支情况
     * 在这里一览无余；点击某项即可删除。
     */
    private fun showBudgetManagerDialog() {
        val progressList = viewModel.budgetProgressList.value.orEmpty()

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.budget_manager)
            .setNeutralButton(R.string.budget_add) { _, _ -> showAddBudgetDialog() }
            .setNegativeButton(R.string.close, null)

        if (progressList.isEmpty()) {
            builder.setMessage(R.string.budget_empty_hint)
        } else {
            builder.setItems(progressList.map { formatBudgetLabel(it) }.toTypedArray()) { _, which ->
                progressList.getOrNull(which)?.let { showDeleteBudgetDialog(it) }
            }
        }
        builder.show()
    }

    private fun formatBudgetLabel(progress: BudgetProgress): String {
        val rule = progress.rule
        val scope = rule.categoryName ?: getString(R.string.budget_scope_overall)
        val period = getString(
            if (rule.period == BudgetPeriod.MONTH) R.string.budget_period_month
            else R.string.budget_period_week
        )
        val stateMark = when (progress.state) {
            BudgetState.OVER -> getString(R.string.budget_state_over)
            BudgetState.WARNING -> getString(R.string.budget_state_warning)
            BudgetState.NORMAL -> ""
        }
        return "$scope · $period\n已花 ${LedgerStats.formatAmount(progress.spentCents)} / " +
            "${LedgerStats.formatAmount(rule.limitCents)}（${progress.percent}%）$stateMark"
    }

    private fun showDeleteBudgetDialog(progress: BudgetProgress) {
        val scope = progress.rule.categoryName ?: getString(R.string.budget_scope_overall)
        val period = getString(
            if (progress.rule.period == BudgetPeriod.MONTH) R.string.budget_period_month
            else R.string.budget_period_week
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.budget_delete_title)
            .setMessage(getString(R.string.budget_delete_message, "$scope · $period"))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteBudget(progress.rule)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 新增预算：适用范围（总预算或某个支出分类）+ 周期（每月 / 每周）+ 限额。 */
    private fun showAddBudgetDialog() {
        val context = requireContext()
        val scopeOptions = buildList {
            add(getString(R.string.budget_scope_overall))
            addAll(budgetCategoryNames)
        }
        val periodOptions = listOf(
            getString(R.string.budget_period_month),
            getString(R.string.budget_period_week)
        )

        val scopeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                scopeOptions
            )
        }
        val periodSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                periodOptions
            )
        }
        val amountEdit = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.budget_limit_hint)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 8, (24 * density).toInt(), 0)
            addView(formLabel(R.string.budget_scope))
            addView(scopeSpinner)
            addView(formLabel(R.string.budget_period))
            addView(periodSpinner)
            addView(formLabel(R.string.budget_limit))
            addView(amountEdit)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.budget_add)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = amountEdit.text.toString().toDoubleOrNull()
                when {
                    amount == null -> Toast.makeText(
                        context, getString(R.string.enter_valid_number), Toast.LENGTH_SHORT
                    ).show()

                    amount <= 0 -> Toast.makeText(
                        context, getString(R.string.budget_must_positive), Toast.LENGTH_SHORT
                    ).show()

                    else -> {
                        val scopeIndex = scopeSpinner.selectedItemPosition
                        viewModel.saveBudget(
                            BudgetRule(
                                period = if (periodSpinner.selectedItemPosition == 1) {
                                    BudgetPeriod.WEEK
                                } else {
                                    BudgetPeriod.MONTH
                                },
                                categoryName = if (scopeIndex == 0) {
                                    null
                                } else {
                                    scopeOptions.getOrNull(scopeIndex)
                                },
                                limitCents = (amount * 100).roundToLong()
                            )
                        )
                        Toast.makeText(
                            context, getString(R.string.budget_updated), Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val density: Float get() = resources.displayMetrics.density

    /** 表单小标题（预算新增对话框用）。 */
    private fun formLabel(textRes: Int): TextView = TextView(requireContext()).apply {
        setText(textRes)
        setTextColor(
            ThemeColors.of(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                R.color.text_secondary
            )
        )
        textSize = 12f
        setPadding(0, (10 * density).toInt(), 0, 0)
    }

    private fun showDeleteConfirmDialog(item: LedgerItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_record)
            .setMessage(R.string.delete_record_confirm)
            .setPositiveButton(R.string.delete_record) { _, _ ->
                viewModel.deleteLedgerEntry(item)
                Snackbar.make(binding.coordinatorLayout, R.string.deleted_message, Snackbar.LENGTH_LONG)
                    .setAction(R.string.delete_undo) {
                        viewModel.restoreLedgerEntry(item)
                    }
                    .setAnchorView(binding.fabAdd)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupMenuProvider() {
        val menuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.home_menu, menu)
                val searchItem = menu.findItem(R.id.action_search)
                val searchView = searchItem.actionView as SearchView
                searchView.queryHint = getString(R.string.search_hint)
                searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean {
                        viewModel.setSearchQuery(query.orEmpty())
                        return true
                    }

                    override fun onQueryTextChange(newText: String?): Boolean {
                        viewModel.setSearchQuery(newText.orEmpty())
                        return true
                    }
                })
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                when (menuItem.itemId) {
                    R.id.action_auto_bookkeeping -> {
                        showAutoBookkeepingDialog()
                        true
                    }

                    R.id.action_theme -> {
                        showThemeDialog()
                        true
                    }

                    R.id.action_budget_alert -> {
                        showBudgetAlertDialog()
                        true
                    }

                    else -> false
                }
        }

        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showAutoBookkeepingDialog() {
        val accessGranted = isNotificationAccessGranted()

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
        }

        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.auto_bookkeeping_desc)
            setTextColor(ThemeColors.of(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary))
            textSize = 14f
        })

        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.auto_bookkeeping_permission) + "：" +
                getString(
                    if (accessGranted) R.string.auto_bookkeeping_permission_granted
                    else R.string.auto_bookkeeping_permission_denied
                )
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (accessGranted) R.color.income else R.color.expense
                )
            )
            textSize = 14f
            setPadding(0, 24, 0, 0)
        })

        if (!accessGranted) {
            content.addView(Button(requireContext()).apply {
                text = getString(R.string.auto_bookkeeping_grant)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            })
        }

        val switch = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.auto_bookkeeping_switch)
            isChecked = viewModel.autoBookkeepingEnabled.value ?: false
        }
        var suppressing = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (suppressing) return@setOnCheckedChangeListener
            if (!accessGranted) {
                suppressing = true
                switch.isChecked = false
                suppressing = false
                Toast.makeText(requireContext(), R.string.auto_bookkeeping_need_permission, Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                viewModel.setAutoBookkeepingEnabled(checked)
                Toast.makeText(
                    requireContext(),
                    if (checked) R.string.auto_bookkeeping_enabled_toast
                    else R.string.auto_bookkeeping_disabled_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        content.addView(switch)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.auto_bookkeeping)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * 「外观」设置：主题模式（单选）+ 动态取色开关。
     *
     * 动态取色默认关闭——本应用有一套固定的天蓝配色与分类色板，视觉识别度是产品的一部分，
     * 所以做成显式可选项。切换后只需重建当前 Activity 即可生效（见 [App] 的 precondition）。
     */
    private fun showThemeDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        // 直接读 ViewModel 里由 ThemeSettings 同步播种的状态：无论有没有观察者，
        // 值都是用户上次保存的那一档（不再依赖「被观察过」才拿得到值）。
        val current = ThemeSettings.normalize(viewModel.themeMode.value)

        val selectedIndex = intArrayOf(ThemeSettings.indexOf(current))
        val modeGroup = buildThemeModeRadioGroup(context, current) { selectedIndex[0] = it }

        val dynamicAvailable = ThemeSettings.isDynamicColorAvailable()
        val dynamicSwitch = SwitchMaterial(context).apply {
            text = getString(
                if (dynamicAvailable) R.string.dynamic_color
                else R.string.dynamic_color_unavailable
            )
            isChecked = dynamicAvailable && ThemeSettings.isDynamicColorEnabled(context)
            isEnabled = dynamicAvailable
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(modeGroup)
            addView(dynamicSwitch)
            addView(
                TextView(context).apply {
                    text = getString(R.string.dynamic_color_hint)
                    setTextColor(ThemeColors.of(context, com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, 0, 0, (4 * density).toInt())
                }
            )
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.theme_mode)
            .setView(content)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val dynamicBefore = ThemeSettings.isDynamicColorEnabled(context)
                ThemeSettings.saveDynamicColorEnabled(context, dynamicSwitch.isChecked)

                val newMode = ThemeSettings.fromIndex(selectedIndex[0])
                when {
                    // setThemeMode 内部会调用 setDefaultNightMode，系统会自动重建 Activity
                    newMode != current -> viewModel.setThemeMode(newMode)
                    // 仅动态取色变化时，手动重建以让主题叠加生效
                    dynamicSwitch.isChecked != dynamicBefore -> requireActivity().recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 「预算提醒」设置：开关 + 通知权限处理。
     *
     * 开启前必须先拿到通知权限，否则定时任务会「算出来却发不出去」，
     * 用户会以为功能坏了。因此这里先申请权限，拿到之后才写入开关。
     */
    private fun showBudgetAlertDialog() {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 8, (24 * density).toInt(), 0)
        }
        content.addView(TextView(context).apply {
            text = getString(R.string.budget_alert_desc)
            setTextColor(
                ThemeColors.of(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    R.color.text_secondary
                )
            )
            textSize = 13f
        })

        val switch = SwitchMaterial(context).apply {
            text = getString(R.string.budget_alert_switch)
            isChecked = BudgetAlertSettings.isEnabled(context)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        var suppressing = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (suppressing) return@setOnCheckedChangeListener
            if (checked && !notificationsAllowed()) {
                // 权限没拿到就不保存开关，避免出现「已开启但收不到通知」的假象
                suppressing = true
                switch.isChecked = false
                suppressing = false
                requestNotificationPermission()
                return@setOnCheckedChangeListener
            }
            BudgetAlertSettings.setEnabled(context, checked)
            BudgetAlertWorker.sync(context)
        }
        content.addView(switch)

        AlertDialog.Builder(context)
            .setTitle(R.string.budget_alert)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // API 33 以下没有运行时权限，只能是用户在系统设置里关掉了通知
            Toast.makeText(requireContext(), R.string.budget_alert_need_permission, Toast.LENGTH_SHORT)
                .show()
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
            )
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(requireContext().packageName)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 构建「外观」弹窗里的主题模式单选项（跟随系统 / 浅色 / 深色）。
 *
 * 抽成独立的顶层函数是为了能被仪器化测试直接驱动：下面两条约束来自 `RadioGroup` 的
 * 实现细节，任何一条写错都会表现为「选了别的档，原来那档还勾着」，也就是
 * 「在切换深浅外观时始终显示跟随系统」这个 bug。
 *
 * 1. **每个 RadioButton 必须先分配 id 再 addView。**
 *    `RadioGroup.addView` 在 `super.addView` 之前就读 `button.getId()`；此时未分配 id 的
 *    子项返回 `View.NO_ID(-1)`，于是 RadioGroup 内部的 `mCheckedId` 被写成 -1。
 *    而真正生成 id 的 `PassThroughHierarchyChangeListener.onChildViewAdded`
 *    要等到 `super.addView` 阶段才执行，已经晚了一步。
 *    此后用户点其它项时，`CheckedStateTracker` 会因为 `mCheckedId == -1` 而
 *    **跳过「取消上一项勾选」**这一步，最初勾上的那一项就再也摘不掉。
 * 2. **初始勾选必须在所有子项 addView 之后设置。**
 *    理由同上：只有子项已经带着真实 id 挂在 RadioGroup 里，`isChecked = true` 触发的
 *    `CheckedStateTracker` 才会把 `mCheckedId` 正确记录成该项的 id。
 *
 * @param current 当前已保存的主题模式，用于决定初始勾选项（内部会先做一次收敛）。
 * @param onSelect 用户点选某一档时回调其下标，顺序与 [ThemeSettings.labels] 一致。
 */
internal fun buildThemeModeRadioGroup(
    context: Context,
    current: String,
    onSelect: (Int) -> Unit = {}
): RadioGroup {
    val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
    val buttons = ThemeSettings.labels().mapIndexed { index, label ->
        RadioButton(context).apply {
            // 约束 1：id 必须在 addView 之前就绪，否则 RadioGroup 的互斥会失效。
            id = View.generateViewId()
            text = label
            setOnClickListener { onSelect(index) }
        }
    }
    buttons.forEach(group::addView)
    // 约束 2：等全部子项都挂好（id 已就绪）之后再勾选初始项。
    buttons[ThemeSettings.indexOf(ThemeSettings.normalize(current))].isChecked = true
    return group
}

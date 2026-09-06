package com.example.personalledger

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.widget.SearchView
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

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

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
        setupObservers()
        setupFab()
        setupBudgetClick()
        setupMenuProvider()
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
            binding.layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.todayIncome.observe(viewLifecycleOwner) { income ->
            binding.textTodayIncome.text = "+$income"
        }

        viewModel.todayExpense.observe(viewLifecycleOwner) { expense ->
            binding.textTodayExpense.text = "-$expense"
        }

        viewModel.budget.observe(viewLifecycleOwner) { budget ->
            if (budget > 0) {
                binding.textBudgetLabel.text = getString(
                    R.string.budget_amount,
                    getString(R.string.currency_symbol),
                    String.format(Locale.getDefault(), "%.2f", budget)
                )
                binding.layoutBudget.visibility = View.VISIBLE
            } else {
                binding.textBudgetLabel.text = getString(R.string.budget_not_set)
                binding.layoutBudget.visibility = View.GONE
            }
        }

        viewModel.budgetProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressBudget.progress = progress.toInt()
            binding.textBudgetPercent.text = getString(R.string.percent_value, progress.toInt())
            updateProgressColor(progress)
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

    private fun buildFilterOptions(): List<String> {
        return mutableListOf("全部", "支出", "收入").apply {
            addAll(
                viewModel.historyList.value
                    .orEmpty()
                    .map { it.categoryName }
                    .filter { it.isNotBlank() }
                    .distinct()
            )
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
            ContextCompat.getColor(requireContext(), R.color.md_theme_tertiary)
        } else {
            ContextCompat.getColor(requireContext(), R.color.text_secondary)
        }
        binding.textAmountFilter.setTextColor(color)
    }

    private fun showDateFilterDialog() {
        val (from, to) = viewModel.currentDateRange()
        val rangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.date_filter))
            .apply {
                if (from != null && to != null) {
                    setSelection(androidx.core.util.Pair(from, to))
                }
            }
            .build()

        rangePicker.addOnPositiveButtonClickListener { selection ->
            viewModel.setDateRange(selection.first, selection.second)
            updateDateFilterTint()
        }

        rangePicker.show(childFragmentManager, "date_range_picker")
    }

    private fun updateDateFilterTint() {
        val (from, to) = viewModel.currentDateRange()
        val active = from != null || to != null
        val color = if (active) {
            ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
        } else {
            ContextCompat.getColor(requireContext(), R.color.text_secondary)
        }
        binding.textDateFilter.setTextColor(color)
    }

    private fun updateProgressColor(progress: Float) {
        val tintColor = when {
            progress >= 100 -> ContextCompat.getColor(requireContext(), R.color.expense)
            progress >= 80 -> ContextCompat.getColor(requireContext(), R.color.warning)
            else -> ContextCompat.getColor(requireContext(), R.color.income)
        }
        binding.progressBudget.progressTintList = ColorStateList.valueOf(tintColor)
        binding.progressBudget.invalidate()
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            AddEntryBottomSheetDialogFragment()
                .show(childFragmentManager, AddEntryBottomSheetDialogFragment.TAG)
        }
    }

    private fun setupBudgetClick() {
        binding.layoutBudgetClick.setOnClickListener { showBudgetDialog() }
    }

    private fun showBudgetDialog() {
        val currentBudget = viewModel.budget.value ?: 0.0
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.enter_budget_hint)
            if (currentBudget > 0) setText(String.format(Locale.getDefault(), "%.2f", currentBudget))
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.set_month_budget)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val budget = editText.text.toString().toDoubleOrNull()
                when {
                    budget == null -> Toast.makeText(requireContext(), getString(R.string.enter_valid_number), Toast.LENGTH_SHORT).show()
                    budget <= 0 -> Toast.makeText(requireContext(), getString(R.string.budget_must_positive), Toast.LENGTH_SHORT).show()
                    else -> {
                        viewModel.saveBudget(budget)
                        Toast.makeText(requireContext(), getString(R.string.budget_updated), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(item: LedgerItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_record)
            .setMessage(R.string.delete_record_confirm)
            .setPositiveButton(R.string.delete_record) { _, _ ->
                viewModel.deleteLedgerEntry(item)
                Snackbar.make(binding.coordinatorLayout, R.string.deleted_message, Snackbar.LENGTH_LONG)
                    .setAction(R.string.delete_undo) {
                        viewModel.addLedgerEntry(item)
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
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
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

    private fun showThemeDialog() {
        val current = viewModel.themeMode.value ?: ThemeSettings.SYSTEM
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.theme_mode)
            .setSingleChoiceItems(ThemeSettings.labels(), ThemeSettings.indexOf(current)) { dialog, which ->
                viewModel.setThemeMode(ThemeSettings.fromIndex(which))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

package com.example.personalledger

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalledger.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class HomeFragment : Fragment() {

    private sealed class LedgerFilter {
        data object All : LedgerFilter()
        data object Expense : LedgerFilter()
        data object Income : LedgerFilter()
        data class Category(val name: String) : LedgerFilter()
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var currentFilter: LedgerFilter = LedgerFilter.All

    private val ledgerAdapter = LedgerAdapter { item, _ ->
        showDeleteConfirmDialog(item)
    }

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
        viewModel.historyList.observe(viewLifecycleOwner) { list ->
            val filteredList = filterLedgerList(list)
            ledgerAdapter.submitList(filteredList)
            binding.layoutEmpty.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
            updateBoardStats(list)
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
    }

    private fun setupFilterClick() {
        binding.textViewAll.setOnClickListener {
            showFilterDialog()
        }
    }

    private fun filterLedgerList(list: List<LedgerItem>): List<LedgerItem> {
        return when (val filter = currentFilter) {
            LedgerFilter.All -> list
            LedgerFilter.Expense -> list.filter { it.isExpense }
            LedgerFilter.Income -> list.filter { !it.isExpense }
            is LedgerFilter.Category -> list.filter { it.categoryName == filter.name }
        }
    }

    private fun showFilterDialog() {
        val filterOptions = buildFilterOptions()
        val checkedItem = getCheckedFilterIndex(filterOptions)

        AlertDialog.Builder(requireContext())
            .setTitle("筛选类型")
            .setSingleChoiceItems(filterOptions.toTypedArray(), checkedItem) { dialog, which ->
                currentFilter = when (which) {
                    1 -> LedgerFilter.Expense
                    2 -> LedgerFilter.Income
                    in 3 until filterOptions.size -> LedgerFilter.Category(filterOptions[which])
                    else -> LedgerFilter.All
                }
                viewModel.historyList.value?.let { ledgerAdapter.submitList(filterLedgerList(it)) }
                updateEmptyState()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun getCheckedFilterIndex(filterOptions: List<String>): Int {
        return when (val filter = currentFilter) {
            LedgerFilter.All -> 0
            LedgerFilter.Expense -> 1
            LedgerFilter.Income -> 2
            is LedgerFilter.Category -> filterOptions.indexOf(filter.name).takeIf { it >= 0 } ?: 0
        }
    }

    private fun updateEmptyState() {
        val currentList = viewModel.historyList.value.orEmpty()
        val filteredList = filterLedgerList(currentList)
        binding.layoutEmpty.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateBoardStats(list: List<LedgerItem>) {
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Calendar.getInstance().time)

        var monthIncome = 0.0
        var monthExpense = 0.0

        list.forEach { item ->
            if (!item.time.startsWith(monthStr)) return@forEach
            val amount = item.amount.toDoubleOrNull() ?: 0.0
            if (item.isExpense) monthExpense += amount else monthIncome += amount
        }

        val balance = monthIncome - monthExpense
        val amountColor = if (balance >= 0) {
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
        binding.textAmount.text = String.format(Locale.getDefault(), "%.2f", abs(balance))
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
        binding.layoutBudgetClick.setOnClickListener {
            showBudgetDialog()
        }
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
                Toast.makeText(context, getString(R.string.record_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupMenuProvider() {
        val menuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = Unit

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
        }

        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

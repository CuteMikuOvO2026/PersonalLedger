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
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.core.view.MenuProvider
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalledger.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class HomeFragment : Fragment() {

    private enum class LedgerFilterType {
        ALL,
        EXPENSE,
        INCOME
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var currentFilterType = LedgerFilterType.ALL

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
        return when (currentFilterType) {
            LedgerFilterType.ALL -> list
            LedgerFilterType.EXPENSE -> list.filter { it.isExpense }
            LedgerFilterType.INCOME -> list.filter { !it.isExpense }
        }
    }

    private fun showFilterDialog() {
        val filterOptions = arrayOf("全部", "支出", "收入")
        val checkedItem = when (currentFilterType) {
            LedgerFilterType.ALL -> 0
            LedgerFilterType.EXPENSE -> 1
            LedgerFilterType.INCOME -> 2
        }

        AlertDialog.Builder(requireContext())
            .setTitle("筛选类型")
            .setSingleChoiceItems(filterOptions, checkedItem) { dialog, which ->
                currentFilterType = when (which) {
                    1 -> LedgerFilterType.EXPENSE
                    2 -> LedgerFilterType.INCOME
                    else -> LedgerFilterType.ALL
                }
                viewModel.historyList.value?.let { ledgerAdapter.submitList(filterLedgerList(it)) }
                updateEmptyState()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

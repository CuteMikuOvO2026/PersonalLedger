package com.example.personalledger

import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalledger.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

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
        setupObservers()
        setupFab()
        setupBudgetClick()
        setupMenuProvider()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ledgerAdapter
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
        }
    }

    private fun setupObservers() {
        viewModel.historyList.observe(viewLifecycleOwner) { list ->
            ledgerAdapter.submitList(list)
            binding.layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
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
                binding.textBudgetLabel.text = "¥$budget"
                binding.layoutBudget.visibility = View.VISIBLE
            } else {
                binding.textBudgetLabel.text = "未设置"
                binding.layoutBudget.visibility = View.GONE
            }
        }

        viewModel.budgetProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressBudget.progress = progress.toInt()
            binding.textBudgetPercent.text = "${progress.toInt()}%"
            updateProgressColor(progress)
        }

        viewModel.expenseThisMonth.observe(viewLifecycleOwner) { expense ->
            binding.textExpenseThisMonth.text = "已支出 ¥$expense"
        }
    }

    private fun updateBoardStats(list: List<LedgerItem>) {
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Calendar.getInstance().time)

        var monthIncome = 0
        var monthExpense = 0

        list.forEach { item ->
            if (!item.time.startsWith(monthStr)) return@forEach
            val amount = item.amount.toDoubleOrNull()?.toInt() ?: 0
            if (item.isExpense) {
                monthExpense += amount
            } else {
                monthIncome += amount
            }
        }

        val balance = monthIncome - monthExpense
        if (balance >= 0) {
            binding.textBoardTitle.text = "本月结余"
            binding.textAmountSign.text = "+"
            binding.textAmountSign.setTextColor(Color.parseColor("#A5D6A7"))
            binding.textAmount.setTextColor(Color.parseColor("#A5D6A7"))
            binding.textAmountCurrency.setTextColor(Color.parseColor("#A5D6A7"))
        } else {
            binding.textBoardTitle.text = "本月超支"
            binding.textAmountSign.text = "-"
            binding.textAmountSign.setTextColor(Color.parseColor("#FFCDD2"))
            binding.textAmount.setTextColor(Color.parseColor("#FFCDD2"))
            binding.textAmountCurrency.setTextColor(Color.parseColor("#FFCDD2"))
        }

        binding.textAmount.text = String.format(Locale.getDefault(), "%.2f", kotlin.math.abs(balance).toDouble())
    }

    private fun updateProgressColor(progress: Float) {
        val tintColor = when {
            progress >= 100 -> Color.parseColor("#D32F2F")
            progress >= 80 -> Color.parseColor("#F57C00")
            else -> Color.parseColor("#388E3C")
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
        val currentBudget = viewModel.budget.value ?: 0
        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "请输入预算金额"
            if (currentBudget > 0) {
                setText(currentBudget.toString())
            }
            setPadding(64, 32, 64, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("设置本月预算")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val budget = editText.text.toString().toIntOrNull()
                when {
                    budget == null -> Toast.makeText(requireContext(), "请输入有效数字", Toast.LENGTH_SHORT).show()
                    budget <= 0 -> Toast.makeText(requireContext(), "预算必须大于 0", Toast.LENGTH_SHORT).show()
                    else -> {
                        viewModel.saveBudget(budget)
                        Toast.makeText(requireContext(), "预算已更新", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirmDialog(item: LedgerItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定要删除这条记录吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteLedgerEntry(item)
                Toast.makeText(context, "记录已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
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

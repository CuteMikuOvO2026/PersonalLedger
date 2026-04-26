package com.example.personalledger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.example.personalledger.databinding.BottomSheetInputBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddEntryBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInputBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val categoryAdapter = CategoryAdapter { }

    private var isExpense = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupCategoryList()
        setupActions()
        resetForm()
        applyInsets()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding.editAmount.requestFocus()
    }

    private fun setupTabs() {
        if (binding.tabType.tabCount == 0) {
            binding.tabType.addTab(binding.tabType.newTab().setText("支出"))
            binding.tabType.addTab(binding.tabType.newTab().setText("收入"))
        }

        binding.tabType.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isExpense = tab?.position != 1
                updateCategories()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun setupCategoryList() {
        binding.recyclerCategories.apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = categoryAdapter
        }
    }

    private fun setupActions() {
        binding.buttonClose.setOnClickListener { dismiss() }
        binding.buttonSave.setOnClickListener { saveEntry() }
    }

    private fun resetForm() {
        binding.editAmount.setText("")
        binding.editNote.setText("")
        binding.tabType.selectTab(binding.tabType.getTabAt(0))
        isExpense = true
        updateCategories()
    }

    private fun updateCategories() {
        val categories = if (isExpense) {
            viewModel.expenseCategories
        } else {
            viewModel.incomeCategories
        }
        categoryAdapter.submitList(categories)
    }

    private fun saveEntry() {
        val amountStr = binding.editAmount.text?.toString()?.trim().orEmpty()
        val note = binding.editNote.text?.toString()?.trim().orEmpty()

        if (amountStr.isEmpty()) {
            Toast.makeText(context, "请输入金额", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null) {
            Toast.makeText(context, "金额格式不正确", Toast.LENGTH_SHORT).show()
            return
        }

        if (amount <= 0) {
            Toast.makeText(context, "金额必须大于 0", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedCategory = categoryAdapter.getSelectedCategory()
        if (selectedCategory == null) {
            Toast.makeText(context, "请选择分类", Toast.LENGTH_SHORT).show()
            return
        }

        val item = LedgerItem(
            time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            amount = String.format(Locale.getDefault(), "%.2f", amount),
            isExpense = isExpense,
            categoryName = selectedCategory.name,
            categoryIconRes = selectedCategory.iconRes,
            note = note
        )

        viewModel.addLedgerEntry(item)
        Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = imeInsets.bottom)
            insets
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddEntryBottomSheet"
    }
}

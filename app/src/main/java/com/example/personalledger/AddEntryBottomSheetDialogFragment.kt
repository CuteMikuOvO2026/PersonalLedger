package com.example.personalledger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import com.example.personalledger.databinding.BottomSheetInputBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AddEntryBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInputBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val categoryAdapter = CategoryAdapter(
        onCategoryClick = { },
        onCustomLongClick = { category -> showDeleteCategoryDialog(category) }
    )

    private var isExpense = true
    private var editingItem: LedgerItem? = null

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
        editingItem = arguments?.let { bundle ->
            if (!bundle.containsKey(ARG_EDIT_TIME)) {
                null
            } else {
                LedgerItem(
                    id = bundle.getString(ARG_EDIT_ID).orEmpty(),
                    amount = bundle.getString(ARG_EDIT_AMOUNT).orEmpty(),
                    note = bundle.getString(ARG_EDIT_NOTE).orEmpty(),
                    time = bundle.getString(ARG_EDIT_TIME).orEmpty(),
                    isExpense = bundle.getBoolean(ARG_EDIT_IS_EXPENSE, true),
                    categoryName = bundle.getString(ARG_EDIT_CATEGORY_NAME).orEmpty(),
                    categoryIconRes = bundle.getInt(ARG_EDIT_CATEGORY_ICON, android.R.drawable.ic_menu_agenda)
                )
            }
        }
        setupTabs()
        setupCategoryList()
        setupActions()
        observeCategories()
        bindInitialState()
        applyInsets()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding.editAmount.requestFocus()
    }

    private fun setupTabs() {
        if (binding.tabType.tabCount == 0) {
            binding.tabType.addTab(binding.tabType.newTab().setText(R.string.type_expense))
            binding.tabType.addTab(binding.tabType.newTab().setText(R.string.type_income))
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
        binding.buttonAddCategory.setOnClickListener { showAddCategoryDialog() }
    }

    private fun setupActions() {
        binding.buttonClose.setOnClickListener { dismiss() }
        binding.buttonSave.setOnClickListener { saveEntry() }
    }

    private fun bindInitialState() {
        val item = editingItem
        if (item == null) {
            binding.editAmount.setText("")
            binding.editNote.setText("")
            binding.tabType.selectTab(binding.tabType.getTabAt(0))
            isExpense = true
            updateCategories()
            return
        }

        binding.editAmount.setText(item.amount)
        binding.editNote.setText(item.note)
        binding.tabType.selectTab(binding.tabType.getTabAt(if (item.isExpense) 0 else 1))
        isExpense = item.isExpense
        updateCategories(item.categoryName)
        binding.buttonSave.text = getString(R.string.edit)
    }

    private fun observeCategories() {
        viewModel.expenseCategories.observe(viewLifecycleOwner) { cats ->
            if (isExpense) {
                val selectedName = categoryAdapter.getSelectedCategory()?.name
                val selectedIndex = cats.indexOfFirst { it.name == selectedName }.takeIf { it != -1 } ?: 0
                categoryAdapter.submitList(cats, selectedIndex)
            }
        }
        viewModel.incomeCategories.observe(viewLifecycleOwner) { cats ->
            if (!isExpense) {
                val selectedName = categoryAdapter.getSelectedCategory()?.name
                val selectedIndex = cats.indexOfFirst { it.name == selectedName }.takeIf { it != -1 } ?: 0
                categoryAdapter.submitList(cats, selectedIndex)
            }
        }
    }

    private fun updateCategories(selectedCategoryName: String? = null) {
        val liveData = if (isExpense) viewModel.expenseCategories else viewModel.incomeCategories
        val categories = liveData.value.orEmpty()
        val selectedIndex = selectedCategoryName?.let { name ->
            categories.indexOfFirst { it.name == name }.takeIf { it != -1 }
        } ?: 0
        categoryAdapter.submitList(categories, selectedIndex)
    }

    private fun saveEntry() {
        val amountStr = binding.editAmount.text?.toString()?.trim().orEmpty()
        val note = binding.editNote.text?.toString()?.trim().orEmpty()

        when {
            amountStr.isEmpty() -> {
                Toast.makeText(context, getString(R.string.enter_amount), Toast.LENGTH_SHORT).show()
                return
            }

            amountStr.toDoubleOrNull() == null -> {
                Toast.makeText(context, getString(R.string.invalid_amount), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val amount = amountStr.toDouble()
        if (amount <= 0) {
            Toast.makeText(context, getString(R.string.amount_must_positive), Toast.LENGTH_SHORT).show()
            return
        }

        val selectedCategory = categoryAdapter.getSelectedCategory()
        if (selectedCategory == null) {
            Toast.makeText(context, getString(R.string.select_category), Toast.LENGTH_SHORT).show()
            return
        }

        val item = LedgerItem(
            id = editingItem?.id?.ifEmpty { UUID.randomUUID().toString() } ?: UUID.randomUUID().toString(),
            time = editingItem?.time ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            amount = String.format(Locale.getDefault(), "%.2f", amount),
            isExpense = isExpense,
            categoryName = selectedCategory.name,
            categoryIconRes = selectedCategory.iconRes,
            note = note
        )

        val sourceItem = editingItem
        if (sourceItem == null) {
            viewModel.addLedgerEntry(item)
            Toast.makeText(context, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
        } else {
            viewModel.updateLedgerEntry(sourceItem, item)
            Toast.makeText(context, getString(R.string.entry_updated), Toast.LENGTH_SHORT).show()
        }
        dismiss()
    }

    private fun showAddCategoryDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.category_name_hint)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_custom_category)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.category_name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.length > 8) {
                    Toast.makeText(requireContext(), getString(R.string.category_name_too_long), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = if (isExpense) "expense" else "income"
                viewModel.addCustomCategory(name, R.drawable.ic_other, type)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteCategoryDialog(category: CategoryItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_category_title)
            .setMessage(getString(R.string.delete_category_message, category.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.removeCustomCategory(category)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        private const val ARG_EDIT_AMOUNT = "arg_edit_amount"
        private const val ARG_EDIT_NOTE = "arg_edit_note"
        private const val ARG_EDIT_TIME = "arg_edit_time"
        private const val ARG_EDIT_IS_EXPENSE = "arg_edit_is_expense"
        private const val ARG_EDIT_CATEGORY_NAME = "arg_edit_category_name"
        private const val ARG_EDIT_CATEGORY_ICON = "arg_edit_category_icon"
        private const val ARG_EDIT_ID = "arg_edit_id"

        fun newInstance(item: LedgerItem): AddEntryBottomSheetDialogFragment {
            return AddEntryBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_EDIT_AMOUNT, item.amount)
                    putString(ARG_EDIT_NOTE, item.note)
                    putString(ARG_EDIT_TIME, item.time)
                    putBoolean(ARG_EDIT_IS_EXPENSE, item.isExpense)
                    putString(ARG_EDIT_CATEGORY_NAME, item.categoryName)
                    putInt(ARG_EDIT_CATEGORY_ICON, item.categoryIconRes)
                    putString(ARG_EDIT_ID, item.id)
                }
            }
        }
    }
}

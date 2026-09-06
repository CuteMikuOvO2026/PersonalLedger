package com.example.personalledger

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.UUID
import kotlin.math.roundToLong

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
            if (!bundle.containsKey(ARG_EDIT_TIME_MILLIS)) {
                null
            } else {
                LedgerItem(
                    id = bundle.getString(ARG_EDIT_ID).orEmpty(),
                    amountCents = bundle.getLong(ARG_EDIT_AMOUNT_CENTS, 0L),
                    note = bundle.getString(ARG_EDIT_NOTE).orEmpty(),
                    timeMillis = bundle.getLong(ARG_EDIT_TIME_MILLIS, 0L),
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
            timeMillis = editingItem?.timeMillis ?: System.currentTimeMillis(),
            amountCents = (amount * 100).roundToLong(),
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
        val context = requireContext()
        val editText = EditText(context).apply {
            hint = getString(R.string.category_name_hint)
            setPadding(48, 32, 48, 32)
        }

        val selectedColor = intArrayOf(CategoryColors.pickerPalette[8]) // 默认给一个柔和绿
        val colorPicker = buildColorPicker(selectedColor[0]) { selectedColor[0] = it }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(editText)
            addView(colorPicker)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.add_custom_category)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(context, getString(R.string.category_name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.length > 8) {
                    Toast.makeText(context, getString(R.string.category_name_too_long), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = if (isExpense) "expense" else "income"
                viewModel.addCustomCategory(name, R.drawable.ic_other, type, selectedColor[0])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 构建「选择颜色」色板（横向可滚动），返回一个带标题的分组。 */
    private fun buildColorPicker(initialColor: Int, onSelect: (Int) -> Unit): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val size = (40 * density).toInt()
        val margin = (8 * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val swatches = mutableListOf<View>()
        val selectedIndex = intArrayOf(
            CategoryColors.pickerPalette.indexOfFirst { it == initialColor }.coerceAtLeast(0)
        )

        CategoryColors.pickerPalette.forEachIndexed { index, color ->
            val swatch = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                    .apply { setMargins(margin, margin, margin, margin) }
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.choose_color) + " " + (index + 1)
            }
            swatch.background = ovalDrawable(color, index == selectedIndex[0])
            swatch.setOnClickListener {
                selectedIndex[0] = index
                swatches.forEachIndexed { i, sv ->
                    sv.background = ovalDrawable(CategoryColors.pickerPalette[i], i == index)
                }
                onSelect(CategoryColors.pickerPalette[index])
            }
            swatches.add(swatch)
            row.addView(swatch)
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = getString(R.string.choose_color)
                setPadding((16 * density).toInt(), (16 * density).toInt(), 0, 0)
            })
            addView(scroll)
        }
    }

    private fun ovalDrawable(color: Int, selected: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        val stroke = (3 * density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(stroke, if (selected) 0xFF1E2A24.toInt() else 0x00000000)
        }
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
        private const val ARG_EDIT_AMOUNT_CENTS = "arg_edit_amount_cents"
        private const val ARG_EDIT_NOTE = "arg_edit_note"
        private const val ARG_EDIT_TIME_MILLIS = "arg_edit_time_millis"
        private const val ARG_EDIT_IS_EXPENSE = "arg_edit_is_expense"
        private const val ARG_EDIT_CATEGORY_NAME = "arg_edit_category_name"
        private const val ARG_EDIT_CATEGORY_ICON = "arg_edit_category_icon"
        private const val ARG_EDIT_ID = "arg_edit_id"

        fun newInstance(item: LedgerItem): AddEntryBottomSheetDialogFragment {
            return AddEntryBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_EDIT_AMOUNT_CENTS, item.amountCents)
                    putString(ARG_EDIT_NOTE, item.note)
                    putLong(ARG_EDIT_TIME_MILLIS, item.timeMillis)
                    putBoolean(ARG_EDIT_IS_EXPENSE, item.isExpense)
                    putString(ARG_EDIT_CATEGORY_NAME, item.categoryName)
                    putInt(ARG_EDIT_CATEGORY_ICON, item.categoryIconRes)
                    putString(ARG_EDIT_ID, item.id)
                }
            }
        }
    }
}

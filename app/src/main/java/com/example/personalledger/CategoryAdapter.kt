package com.example.personalledger

import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.personalledger.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategoryClick: (CategoryItem) -> Unit,
    private val onCustomLongClick: ((CategoryItem) -> Unit)? = null
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private var categories: List<CategoryItem> = emptyList()
    private var selectedPosition = 0

    fun submitList(newList: List<CategoryItem>, defaultSelected: Int = 0) {
        categories = newList
        selectedPosition = defaultSelected
        notifyDataSetChanged()
    }

    fun getSelectedCategory(): CategoryItem? =
        if (categories.isNotEmpty()) categories[selectedPosition] else null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding, onCategoryClick, onCustomLongClick)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], position == selectedPosition, position)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding,
        private val onCategoryClick: (CategoryItem) -> Unit,
        private val onCustomLongClick: ((CategoryItem) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: CategoryItem, isSelected: Boolean, position: Int) {
            val context = binding.root.context
            binding.categoryIcon.setImageResource(category.iconRes)
            binding.categoryName.text = category.name

            // 选中态的底色刻意**不迁移**到主题属性：它是「中等蓝底 + 深色文字」的固定配色，
            // 对比度优于 Material 的 primary / onPrimary 组合（浅色模式下 colorOnPrimary 是白字，
            // 压在 #2E9BE0 上反而更难读），而且这个颜色在深色模式下也没有单独取值。
            val selectedBackground = ContextCompat.getColor(context, R.color.surface_chip_selected)
            val selectedText = ThemeColors.of(context, com.google.android.material.R.attr.colorOnSurface, R.color.text_primary)
            // 未选中态按分类取色：同色系淡底 + 分类强调色图标（自定义分类用用户所选颜色）
            val defaultBackground = CategoryColors.containerFor(category.name, category.color)
            val defaultIcon = CategoryColors.accentFor(category.name, category.color)
            val defaultText = ThemeColors.of(context, com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary)

            if (isSelected) {
                binding.categoryIconContainer.setCardBackgroundColor(selectedBackground)
                binding.categoryIcon.setColorFilter(selectedText)
                binding.categoryName.setTextColor(selectedText)
                binding.categoryName.textSize = 14f
            } else {
                binding.categoryIconContainer.setCardBackgroundColor(defaultBackground)
                binding.categoryIcon.setColorFilter(defaultIcon)
                binding.categoryName.setTextColor(defaultText)
                binding.categoryName.textSize = 13f
            }

            binding.root.setOnClickListener {
                val previousPosition = selectedPosition
                selectedPosition = position
                notifyItemChanged(previousPosition)
                notifyItemChanged(position)
                onCategoryClick(category)
                binding.root.isFocusable = false
                binding.root.isFocusableInTouchMode = false
            }

            if (category.isCustom && onCustomLongClick != null) {
                binding.root.setOnLongClickListener {
                    onCustomLongClick(category)
                    true
                }
            }
        }
    }
}

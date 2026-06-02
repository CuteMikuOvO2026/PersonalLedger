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

            val selectedBackground = ContextCompat.getColor(context, R.color.surface_chip_selected)
            val selectedText = ContextCompat.getColor(context, R.color.text_primary)
            val defaultBackground = ContextCompat.getColor(context, R.color.surface_container_low)
            val defaultIcon = ContextCompat.getColor(context, R.color.text_secondary)
            val defaultText = ContextCompat.getColor(context, R.color.text_secondary)

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

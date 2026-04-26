package com.example.personalledger

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onCategoryClick: (CategoryItem) -> Unit
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
        return CategoryViewHolder(binding, onCategoryClick)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], position == selectedPosition, position)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding,
        private val onCategoryClick: (CategoryItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: CategoryItem, isSelected: Boolean, position: Int) {
            binding.categoryIcon.setImageResource(category.iconRes)
            binding.categoryName.text = category.name

            if (isSelected) {
                binding.categoryIconContainer.setCardBackgroundColor(
                    Color.parseColor("#667EEA")
                )
                binding.categoryIcon.setColorFilter(Color.WHITE)
                binding.categoryName.setTextColor(Color.parseColor("#667EEA"))
                binding.categoryName.textSize = 14f
            } else {
                binding.categoryIconContainer.setCardBackgroundColor(
                    Color.parseColor("#F5F5F5")
                )
                binding.categoryIcon.setColorFilter(Color.parseColor("#616161"))
                binding.categoryName.setTextColor(Color.parseColor("#616161"))
                binding.categoryName.textSize = 13f
            }

            binding.root.setOnClickListener {
                val previousPosition = selectedPosition
                selectedPosition = position
                notifyItemChanged(previousPosition)
                notifyItemChanged(position)
                onCategoryClick(category)
                
                // 确保不抢夺焦点
                binding.root.isFocusable = false
                binding.root.isFocusableInTouchMode = false
            }
        }
    }
}

package com.example.personalledger

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.personalledger.databinding.ItemLedgerBinding

class LedgerAdapter(
    private val onDeleteClick: (LedgerItem, Int) -> Unit
) : RecyclerView.Adapter<LedgerAdapter.LedgerViewHolder>() {

    private var items: List<LedgerItem> = emptyList()

    fun submitList(newList: List<LedgerItem>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LedgerViewHolder {
        val binding = ItemLedgerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LedgerViewHolder(binding, onDeleteClick)
    }

    override fun onBindViewHolder(holder: LedgerViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    class LedgerViewHolder(
        private val binding: ItemLedgerBinding,
        private val onDeleteClick: (LedgerItem, Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LedgerItem, position: Int) {
            val context = binding.root.context
            val containerColor = if (item.isExpense) {
                ContextCompat.getColor(context, R.color.expense_container)
            } else {
                ContextCompat.getColor(context, R.color.income_container)
            }
            val accentColor = if (item.isExpense) {
                ContextCompat.getColor(context, R.color.expense)
            } else {
                ContextCompat.getColor(context, R.color.income)
            }

            binding.icon.setImageResource(item.categoryIconRes)
            binding.category.text = item.categoryName
            binding.time.text = item.time
            binding.note.text = item.note.ifEmpty { "-" }
            binding.amount.text = item.amount
            binding.sign.text = if (item.isExpense) "-" else "+"

            binding.iconContainer.setCardBackgroundColor(containerColor)
            binding.icon.imageTintList = ColorStateList.valueOf(accentColor)
            binding.sign.setTextColor(accentColor)
            binding.amount.setTextColor(accentColor)

            binding.root.setOnLongClickListener {
                onDeleteClick(item, position)
                true
            }
        }
    }
}

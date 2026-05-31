package com.example.personalledger

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.ItemLedgerBinding

class LedgerAdapter(
    private val onEditClick: (LedgerItem) -> Unit,
    private val onDeleteClick: (LedgerItem) -> Unit
) : RecyclerView.Adapter<LedgerAdapter.LedgerViewHolder>() {

    private var items: List<LedgerItem> = emptyList()
    private var openPosition = RecyclerView.NO_POSITION

    fun submitList(newList: List<LedgerItem>) {
        items = newList
        if (openPosition >= items.size) {
            openPosition = RecyclerView.NO_POSITION
        }
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): LedgerItem? = items.getOrNull(position)

    fun setOpenPosition(position: Int) {
        if (openPosition == position) return
        val previous = openPosition
        openPosition = position
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
        if (openPosition != RecyclerView.NO_POSITION) notifyItemChanged(openPosition)
    }

    fun closeOpenItem() {
        if (openPosition == RecyclerView.NO_POSITION) return
        val previous = openPosition
        openPosition = RecyclerView.NO_POSITION
        notifyItemChanged(previous)
    }

    fun getOpenPosition(): Int = openPosition

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LedgerViewHolder {
        val binding = ItemLedgerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LedgerViewHolder(binding, onEditClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: LedgerViewHolder, position: Int) {
        holder.bind(items[position], position == openPosition)
    }

    override fun getItemCount(): Int = items.size

    class LedgerViewHolder(
        private val binding: ItemLedgerBinding,
        private val onEditClick: (LedgerItem) -> Unit,
        private val onDeleteClick: (LedgerItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LedgerItem, isOpen: Boolean) {
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

            binding.buttonDelete.setOnClickListener { onDeleteClick(item) }
            binding.foregroundCard.setOnClickListener {
                if (isOpen) {
                    return@setOnClickListener
                }
                onEditClick(item)
            }
            binding.foregroundCard.post {
                binding.foregroundCard.translationX = if (isOpen) {
                    -binding.actionContainer.width.toFloat()
                } else {
                    0f
                }
            }
        }
    }
}

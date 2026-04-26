package com.example.personalledger

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.ItemLedgerBinding

class LedgerAdapter(
    private val onDeleteClick: (LedgerItem, Int) -> Unit
) : RecyclerView.Adapter<LedgerAdapter.LedgerViewHolder>() {

    private var items: List<LedgerItem> = emptyList()

    /**
     * 更新列表数据
     */
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
            // 设置图标和背景色
            binding.icon.setImageResource(item.categoryIconRes)
            binding.category.text = item.categoryName
            binding.time.text = item.time
            binding.note.text = item.note.ifEmpty { "-" }
            binding.amount.text = item.amount

            // 根据收支设置颜色
            if (item.isExpense) {
                binding.iconContainer.setCardBackgroundColor(Color.parseColor("#FFCDD2"))
                binding.icon.setColorFilter(Color.parseColor("#D32F2F"))
                binding.sign.setTextColor(Color.parseColor("#D32F2F"))
                binding.amount.setTextColor(Color.parseColor("#D32F2F"))
            } else {
                binding.iconContainer.setCardBackgroundColor(Color.parseColor("#C8E6C9"))
                binding.icon.setColorFilter(Color.parseColor("#388E3C"))
                binding.sign.setTextColor(Color.parseColor("#388E3C"))
                binding.amount.setTextColor(Color.parseColor("#388E3C"))
            }

            // 长按删除
            binding.root.setOnLongClickListener {
                onDeleteClick(item, position)
                true
            }
        }
    }
}

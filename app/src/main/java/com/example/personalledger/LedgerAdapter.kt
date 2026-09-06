package com.example.personalledger

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.ItemLedgerBinding
import kotlin.math.abs

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
        return LedgerViewHolder(
            binding,
            onEditClick,
            onDeleteClick,
            onRequestOpen = { pos -> setOpenPosition(pos) },
            onRequestClose = { closeOpenItem() },
            getOpenPosition = { openPosition }
        )
    }

    override fun onBindViewHolder(holder: LedgerViewHolder, position: Int) {
        holder.bind(items[position], position == openPosition)
    }

    override fun getItemCount(): Int = items.size

    class LedgerViewHolder(
        private val binding: ItemLedgerBinding,
        private val onEditClick: (LedgerItem) -> Unit,
        private val onDeleteClick: (LedgerItem) -> Unit,
        private val onRequestOpen: (Int) -> Unit,
        private val onRequestClose: () -> Unit,
        private val getOpenPosition: () -> Int
    ) : RecyclerView.ViewHolder(binding.root) {

        private var touchStartX = 0f
        private var touchStartY = 0f
        private var swipeStartTranslation = 0f
        private var isDragging = false

        fun bind(item: LedgerItem, isOpen: Boolean) {
            val context = binding.root.context
            // 分类彩色方案：图标底/图标色按分类取色，金额正负用类型色（收入绿 / 支出红）
            val containerColor = CategoryColors.containerOf(item.categoryName)
            val iconColor = CategoryColors.accentOf(item.categoryName)
            val amountColor = if (item.isExpense) {
                ContextCompat.getColor(context, R.color.expense)
            } else {
                ContextCompat.getColor(context, R.color.income)
            }

            binding.icon.setImageResource(CategoryColors.iconResFor(item.categoryName))
            binding.category.text = item.categoryName
            binding.time.text = item.time
            binding.note.text = item.note.ifEmpty { "-" }
            binding.amount.text = item.amount
            binding.sign.text = if (item.isExpense) "-" else "+"

            binding.iconContainer.setCardBackgroundColor(containerColor)
            binding.icon.imageTintList = ColorStateList.valueOf(iconColor)
            binding.sign.setTextColor(amountColor)
            binding.amount.setTextColor(amountColor)

            binding.buttonDelete.setOnClickListener { onDeleteClick(item) }

            // Reset drag state on rebind
            isDragging = false
            binding.foregroundCard.setOnTouchListener { view, event ->
                handleSwipe(view, event, item)
            }
            binding.foregroundCard.isClickable = true

            binding.foregroundCard.post {
                binding.foregroundCard.translationX = if (isOpen) {
                    -binding.actionContainer.width.toFloat()
                } else {
                    0f
                }
            }
        }

        private fun handleSwipe(view: android.view.View, event: MotionEvent, item: LedgerItem): Boolean {
            val maxSwipe = binding.actionContainer.width.toFloat()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    swipeStartTranslation = view.translationX
                    isDragging = false
                    return false // let parent evaluate click/scroll
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && abs(dx) > abs(dy) * 1.2f && abs(dx) > 12f) {
                        isDragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDragging) {
                        view.translationX = (swipeStartTranslation + dx).coerceIn(-maxSwipe, 0f)
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (isDragging) {
                        val current = view.translationX
                        val wasOpen = getOpenPosition() == adapterPosition
                        val shouldOpen = if (wasOpen) {
                            abs(current) >= maxSwipe * 0.5f
                        } else {
                            abs(current) >= maxSwipe * 0.2f
                        }
                        if (shouldOpen) {
                            onRequestOpen(adapterPosition)
                        } else {
                            onRequestClose()
                        }
                        view.animate()
                            .translationX(if (shouldOpen) -maxSwipe else 0f)
                            .setDuration(200)
                            .start()
                        isDragging = false
                        return true
                    }
                    // Not a drag — treat as tap
                    val isCurrentlyOpen = getOpenPosition() == adapterPosition
                    if (isCurrentlyOpen) {
                        // Tap on open item: close it without editing
                        onRequestClose()
                        view.animate().translationX(0f).setDuration(200).start()
                        return true
                    }
                    // Tap on closed item: edit
                    onEditClick(item)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (isDragging) {
                        onRequestClose()
                        view.animate().translationX(0f).setDuration(200).start()
                        isDragging = false
                        return true
                    }
                    return false
                }
            }
            return false
        }
    }
}

package com.example.personalledger

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.ItemChatMessageBinding
import java.util.Locale

/**
 * 对话列表适配器。
 *
 * 确认卡片里的每一笔账目**程序化生成行**，不嵌套 RecyclerView：嵌套列表会带来第二套
 * adapter / layout manager / 测量协商，而这里每张卡片的行数就是模型解析出的条数（通常 1 条），
 * 收益完全不值这个复杂度。项目在弹窗里已有同样的做法（`AddEntryBottomSheetDialogFragment.buildColorPicker`）。
 */
class ChatAdapter(
    private val onConfirm: (ChatMessage) -> Unit,
    private val onDiscard: (ChatMessage) -> Unit
) : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChatViewHolder(binding, onConfirm, onDiscard)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChatViewHolder(
        private val binding: ItemChatMessageBinding,
        private val onConfirm: (ChatMessage) -> Unit,
        private val onDiscard: (ChatMessage) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val isUser = message.role == ChatRole.USER

            binding.textUser.isVisible = isUser
            binding.layoutAssistant.isVisible = !isUser

            if (isUser) {
                binding.textUser.text = message.content
            } else {
                binding.textAssistant.text = message.content
                bindDraft(message)
            }
        }

        private fun bindDraft(message: ChatMessage) {
            val draft = message.draft
            binding.cardDraft.isVisible = message.hasDraft
            if (draft.isNullOrEmpty()) {
                // 卡片是复用的：不显示时必须清空旧行，否则会串到别的消息上
                binding.layoutDraftEntries.removeAllViews()
                return
            }

            binding.layoutDraftEntries.removeAllViews()
            draft.forEach { entry ->
                binding.layoutDraftEntries.addView(buildEntryRow(entry))
            }

            binding.buttonConfirm.setOnClickListener { onConfirm(message) }
            binding.buttonDiscard.setOnClickListener { onDiscard(message) }
        }

        /** 一行账目：分类图标 + 分类名 + 备注 + 时间，右侧是带符号的金额。 */
        private fun buildEntryRow(entry: ParsedEntry): View {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density

            fun dp(value: Int) = (value * density).toInt()

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }

            row.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                // 图标按分类名解析：与列表渲染、通知自动记账同一条路径，
                // 因此分类名被收敛成「其他」时图标也一起落到兜底图标，不会错配
                setImageResource(CategoryColors.iconResFor(entry.categoryName))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(CategoryColors.containerOf(entry.categoryName))
                }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setColorFilter(CategoryColors.accentOf(entry.categoryName))
            })

            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(10) }

                addView(TextView(context).apply {
                    text = if (entry.note.isNotEmpty()) {
                        "${entry.categoryName} · ${entry.note}"
                    } else {
                        entry.categoryName
                    }
                    setTextColor(ThemeColors.of(context, com.google.android.material.R.attr.colorOnSurface, R.color.text_primary))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })

                addView(TextView(context).apply {
                    // 时间一定要显示：模型把「昨天」解析错时，用户在这一行就能当场看出来
                    text = LedgerItemMappers.formatMillis(entry.timeMillis)
                    setTextColor(ThemeColors.of(context, com.google.android.material.R.attr.colorOnSurfaceVariant, R.color.text_secondary))
                    textSize = 11f
                })
            })

            row.addView(TextView(context).apply {
                val sign = if (entry.isExpense) "-" else "+"
                text = sign + String.format(Locale.getDefault(), "%.2f", entry.amountCents / 100.0)
                setTextColor(
                    ContextCompat.getColor(context, if (entry.isExpense) R.color.expense else R.color.income)
                )
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })

            return row
        }
    }

    companion object {
        /**
         * 比较 [ChatMessage]。`draft` 与 `content` 都在比较范围内：确认 / 忽略之后
         * 同一条消息的 `content` 会被改写、`draft` 会变成 null，必须触发重新绑定，
         * 否则卡片会一直留在界面上。
         */
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }
    }
}

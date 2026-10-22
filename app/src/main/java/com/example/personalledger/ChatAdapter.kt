package com.example.personalledger

import android.graphics.Paint
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
 * 一张消息可能挂四类卡片（[ChatCard]）：新建账目、修改、删除、统计结果。四者的差异只是
 * 「标题 + 行内容 + 有没有确认按钮」，所以**仍然只有一个布局**，按钮的文案与可见性在
 * 这里按卡片类型设置；再加三套 item 布局只会多出三套 ViewBinding 和三个绑定分支。
 *
 * 卡片里的每一笔账目**程序化生成行**，不嵌套 RecyclerView：嵌套列表会带来第二套
 * adapter / layout manager / 测量协商，而这里每张卡片的行数就是卡片上的条数（通常 1 条，
 * 删除卡片最多 20 条），收益完全不值这个复杂度。项目在弹窗里已有同样的做法
 * （`AddEntryBottomSheetDialogFragment.buildColorPicker`）。
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
                return
            }

            // 统计卡片的气泡文字可能为空（模型那句话被刻意丢掉，见 AiToolRunner.summarize）——
            // 空气泡会留下一个没有内容的圆角块，所以整块隐藏。
            binding.textAssistant.isVisible = message.content.isNotBlank()
            binding.textAssistant.text = message.content
            bindCard(message)
        }

        private fun bindCard(message: ChatMessage) {
            val card = message.card
            binding.cardDraft.isVisible = card != null
            // 卡片是复用的：不显示时必须清空旧行，否则会串到别的消息上
            binding.layoutDraftEntries.removeAllViews()
            if (card == null) return

            binding.textCardTitle.setText(
                when (card) {
                    is ChatCard.NewEntries -> R.string.ai_card_title_new
                    is ChatCard.Modify -> R.string.ai_card_title_modify
                    is ChatCard.Remove -> R.string.ai_card_title_delete
                    is ChatCard.Answer -> R.string.ai_card_title_summary
                }
            )
            // 统计卡片的标题是它自己的区间描述（「2026-10-01 ~ 2026-10-31」），比「统计结果」有用
            if (card is ChatCard.Answer && card.title.isNotBlank()) {
                binding.textCardTitle.text = card.title
            }

            binding.buttonConfirm.isVisible = card.pending
            binding.buttonDiscard.isVisible = card.pending
            // 删除没有撤销入口，警告必须出现在按钮之前
            binding.textCardWarning.isVisible = card is ChatCard.Remove

            if (card.pending) {
                binding.buttonConfirm.setText(
                    if (card is ChatCard.Remove) R.string.ai_confirm_delete else R.string.ai_confirm
                )
                binding.buttonConfirm.setOnClickListener { onConfirm(message) }
                binding.buttonDiscard.setOnClickListener { onDiscard(message) }
            }

            when (card) {
                is ChatCard.NewEntries -> card.entries.forEach { entry ->
                    addEntryRow(
                        prefix = null,
                        categoryName = entry.categoryName,
                        note = entry.note,
                        timeMillis = entry.timeMillis,
                        amountCents = entry.amountCents,
                        isExpense = entry.isExpense,
                        strikeThrough = false
                    )
                }

                is ChatCard.Modify -> {
                    val context = binding.root.context
                    // 「原」与「改为」两行并排显示：只看改完之后的样子，用户没法判断这笔是不是他要改的
                    addEntryRow(
                        prefix = context.getString(R.string.ai_label_before),
                        categoryName = card.before.categoryName,
                        note = card.before.note,
                        timeMillis = card.before.timeMillis,
                        amountCents = card.before.amountCents,
                        isExpense = card.before.isExpense,
                        strikeThrough = true
                    )
                    addEntryRow(
                        prefix = context.getString(R.string.ai_label_after),
                        categoryName = card.after.categoryName,
                        note = card.after.note,
                        timeMillis = card.after.timeMillis,
                        amountCents = card.after.amountCents,
                        isExpense = card.after.isExpense,
                        strikeThrough = false
                    )
                }

                is ChatCard.Remove -> card.entries.forEach { entry ->
                    addEntryRow(
                        prefix = null,
                        categoryName = entry.categoryName,
                        note = entry.note,
                        timeMillis = entry.timeMillis,
                        amountCents = entry.amountCents,
                        isExpense = entry.isExpense,
                        strikeThrough = false
                    )
                }

                is ChatCard.Answer -> card.lines.forEach { addTextRow(it) }
            }
        }

        /** 一行账目：分类图标 + 分类名 + 备注 + 时间，右侧是带符号的金额。 */
        private fun addEntryRow(
            prefix: String?,
            categoryName: String,
            note: String,
            timeMillis: Long,
            amountCents: Long,
            isExpense: Boolean,
            strikeThrough: Boolean
        ) {
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
                setImageResource(CategoryColors.iconResFor(categoryName))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(CategoryColors.containerOf(categoryName))
                }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setColorFilter(CategoryColors.accentOf(categoryName))
            })

            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(10) }

                addView(TextView(context).apply {
                    text = buildString {
                        prefix?.let { append(it).append(" · ") }
                        append(categoryName)
                        if (note.isNotEmpty()) append(" · ").append(note)
                    }
                    setTextColor(
                        ThemeColors.of(
                            context,
                            com.google.android.material.R.attr.colorOnSurface,
                            R.color.text_primary
                        )
                    )
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })

                addView(TextView(context).apply {
                    // 时间一定要显示：模型把「昨天」解析错时，用户在这一行就能当场看出来
                    text = LedgerItemMappers.formatMillis(timeMillis)
                    setTextColor(
                        ThemeColors.of(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            R.color.text_secondary
                        )
                    )
                    textSize = 11f
                })
            })

            row.addView(TextView(context).apply {
                val sign = if (isExpense) "-" else "+"
                text = sign + String.format(Locale.getDefault(), "%.2f", amountCents / 100.0)
                setTextColor(
                    ContextCompat.getColor(context, if (isExpense) R.color.expense else R.color.income)
                )
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                // 修改卡片上的「原」金额划掉：一眼看出这是改之前的旧值
                if (strikeThrough) {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
            })

            binding.layoutDraftEntries.addView(row)
        }

        /** 统计结果 / 候选列表的一行文字。 */
        private fun addTextRow(text: String) {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density

            binding.layoutDraftEntries.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(
                    ThemeColors.of(
                        context,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        R.color.text_secondary
                    )
                )
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }
    }

    companion object {
        /**
         * 比较 [ChatMessage]。`card` 与 `content` 都在比较范围内：确认 / 忽略之后
         * 同一条消息的 `content` 会被改写、`card` 会变成 null，必须触发重新绑定，
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

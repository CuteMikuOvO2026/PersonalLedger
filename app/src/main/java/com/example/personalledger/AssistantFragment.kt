package com.example.personalledger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.personalledger.databinding.FragmentAssistantBinding

/**
 * AI 助手页：发一句话，确认后记账。
 *
 * **ViewModel 必须是 Activity 作用域的**（`activityViewModels`），不能是 Fragment 作用域：
 * [MainActivity.switchTo] 每次切页都 `replace` 成一个新 Fragment 实例，Fragment 作用域的
 * ViewModel 会随之被清掉、`viewModelScope` 被取消 —— 表现就是「发完消息切到记账页再切回来，
 * 请求已经死了，助手的回复永远不会出现」。这与 [AddEntryBottomSheetDialogFragment] 用
 * `activityViewModels` 的理由一致。
 */
class AssistantFragment : Fragment() {

    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AssistantViewModel by activityViewModels()

    private val chatAdapter = ChatAdapter(
        onConfirm = { viewModel.confirmDraft(it) },
        onDiscard = { viewModel.discardDraft(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()
        setupList()
        setupInput()
        observe()
        applyInsets()
    }

    private fun setupMenu() {
        val menuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.assistant_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                when (menuItem.itemId) {
                    R.id.action_ai_settings -> {
                        showAiSettingsDialog()
                        true
                    }

                    R.id.action_clear_chat -> {
                        showClearChatDialog()
                        true
                    }

                    else -> false
                }
        }
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupList() {
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = chatAdapter
            // 消息内容变化不改变位置，关掉动画避免确认卡片消失时的闪烁
            itemAnimator = null
        }
    }

    private fun setupInput() {
        binding.buttonSend.setOnClickListener { sendCurrentInput() }
        binding.editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }
    }

    private fun sendCurrentInput() {
        // 正常情况下按钮已被禁用而走不到这里；作为兜底，未配置时直接把用户引到设置弹窗，
        // 而不是让他打完字再吃一个 Toast。
        if (!AiSettings.isConfigured(requireContext())) {
            showAiSettingsDialog()
            return
        }
        val text = binding.editMessage.text?.toString().orEmpty()
        if (text.isBlank()) return
        // 先清空再发送：助手回复是异步的，输入框留着旧文字会让人以为没发出去
        binding.editMessage.setText("")
        viewModel.send(text)
    }

    private fun observe() {
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            val wasAtBottom = isScrolledToBottom()
            chatAdapter.submitList(messages) {
                // 只有本来就在底部时才自动滚动，避免用户翻看历史时被强行拽回最新一条
                if (wasAtBottom) scrollToBottom()
            }
            renderEmptyState(messages.isEmpty())
        }

        viewModel.sending.observe(viewLifecycleOwner) { renderInputState() }

        viewModel.hint.observe(viewLifecycleOwner) { hint ->
            if (hint == null) return@observe
            Toast.makeText(requireContext(), hint, Toast.LENGTH_SHORT).show()
            // 立刻复位：否则旋转屏幕 / 切页回来后同一条提示会再弹一次
            viewModel.clearHint()
        }
    }

    /**
     * 空状态的文案与可点性随「有没有配 API Key」变化。
     *
     * 这一步不能省：没有它，未配置的人打开助手页只会看到一句用法示例，**完全看不出要去右上角
     * 配置**，必须先打一句话、点发送，才会被 Toast 告知。空状态是唯一能事先讲清楚的地方。
     */
    private fun renderEmptyState(hasMessages: Boolean) {
        binding.layoutEmpty.isVisible = !hasMessages
        if (hasMessages) return

        val configured = AiSettings.isConfigured(requireContext())
        binding.textEmptyHint.setText(
            if (configured) R.string.ai_empty_hint else R.string.ai_not_configured
        )
        // 未配置时把提示做成可点的「行动号召」，点一下直接打开设置，省掉「找齿轮」这一步
        binding.textEmptyHint.setTextColor(
            ThemeColors.of(
                requireContext(),
                if (configured) {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                } else {
                    com.google.android.material.R.attr.colorPrimary
                },
                if (configured) R.color.text_secondary else R.color.md_theme_primary
            )
        )
        binding.textEmptyHint.setOnClickListener {
            if (!AiSettings.isConfigured(requireContext())) showAiSettingsDialog()
        }
    }

    /**
     * 输入区的可用状态与提示。
     *
     * **未配置 API Key 时直接禁用输入框与发送按钮**，并把输入框的提示文字换成「请先在右上角填入
     * API Key」。理由是：此时就算让用户打完字点发送，也只会得到一句 Toast 然后什么都不会发生 ——
     * 与其让他白打一遍字，不如从源头堵住并在输入框里就地说明原因。
     *
     * 发送中同样禁用（避免连发两次请求），两种原因合并成一个判据。
     *
     * `TextInputLayout.setEnabled` 会**连带禁用内部的 EditText**（它重写了 setEnabled 并向下传递），
     * 所以这里设外层即可；内层再设一次是防御性冗余，防的是 Material 版本行为变化。
     */
    private fun renderInputState() {
        val configured = AiSettings.isConfigured(requireContext())
        val sending = viewModel.sending.value == true
        val enabled = configured && !sending

        binding.layoutMessage.isEnabled = enabled
        binding.editMessage.isEnabled = enabled
        binding.buttonSend.isEnabled = enabled
        binding.progressSending.isVisible = sending

        binding.layoutMessage.hint = getString(
            if (configured) R.string.ai_input_hint else R.string.ai_input_locked_hint
        )
    }

    private fun isScrolledToBottom(): Boolean {
        val layoutManager = binding.recyclerChat.layoutManager as? LinearLayoutManager ?: return true
        val last = layoutManager.findLastCompletelyVisibleItemPosition()
        return last == RecyclerView.NO_POSITION || last >= chatAdapter.itemCount - 1
    }

    private fun scrollToBottom() {
        val count = chatAdapter.itemCount
        if (count > 0) binding.recyclerChat.scrollToPosition(count - 1)
    }

    /**
     * 「AI 设置」：API Key、模型与隐私说明。
     *
     * 隐私说明不是装饰：这个应用原本全程离线，开启助手意味着**用户的消费描述会离开设备**，
     * 必须在使用前明确讲清楚「什么会发出去、什么不会」，且说明 API Key 已排除在备份之外。
     */
    private fun showAiSettingsDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }

        fun addDescription(text: String, topPadding: Int = 0, isSecondary: Boolean = true) {
            content.addView(TextView(context).apply {
                this.text = text
                textSize = if (isSecondary) 13f else 14f
                setTextColor(
                    ThemeColors.of(
                        context,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        R.color.text_secondary
                    )
                )
                setPadding(0, dp(topPadding), 0, 0)
            })
        }

        addDescription(getString(R.string.ai_settings_desc), isSecondary = false)
        addDescription(getString(R.string.ai_settings_privacy), topPadding = 10)

        val keyEdit = EditText(context).apply {
            hint = getString(R.string.ai_api_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AiSettings.apiKey(context))
            setSingleLine()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        content.addView(keyEdit)

        content.addView(TextView(context).apply {
            text = getString(R.string.ai_get_api_key)
            textSize = 13f
            setTextColor(ThemeColors.of(context, com.google.android.material.R.attr.colorPrimary, R.color.md_theme_primary))
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(API_KEY_URL)))
            }
        })

        val modelGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(TextView(context).apply {
            text = getString(R.string.ai_model)
            textSize = 13f
            setTextColor(
                ThemeColors.of(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    R.color.text_secondary
                )
            )
        })
        val labels = AiSettings.modelLabels()
        val currentIndex = AiSettings.modelIndexOf(AiSettings.model(context))
        labels.forEachIndexed { index, label ->
            modelGroup.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                isChecked = index == currentIndex
                tag = index
            })
        }
        content.addView(modelGroup)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.ai_settings)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        // 手动接管「保存」：校验不通过时不能关闭弹窗，否则用户以为存上了其实没存
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = keyEdit.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(context, R.string.ai_api_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AiSettings.setApiKey(context, key)
            val checkedIndex = modelGroup.checkedRadioButtonId
            val index = if (checkedIndex == -1) 0 else {
                (modelGroup.findViewById<View>(checkedIndex).tag as? Int) ?: 0
            }
            AiSettings.setModel(context, AiSettings.modelFromIndex(index))
            dialog.dismiss()
            // 存完立刻刷新：否则刚配好 Key，页面还挂着「请先填 API Key」且输入框仍是禁用的
            renderEmptyState(chatAdapter.itemCount == 0)
            renderInputState()
        }
    }

    private fun showClearChatDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_clear_chat)
            .setMessage(R.string.ai_clear_chat_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.clearConversation()
                Toast.makeText(requireContext(), R.string.ai_clear_chat_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 让输入条随键盘上移。
     *
     * 这里**刻意不照抄** [AddEntryBottomSheetDialogFragment] 那种「直接把 `imeInsets.bottom`
     * 当内边距」的写法 —— 对本页它是错的：本页的 Fragment 容器底边并不在屏幕底部，它下面还
     * 压着底部导航栏。直接减掉一个完整键盘高度，输入框会悬在键盘上方约一个导航栏的高度。
     *
     * 因此这里量的是现实：本视图底边距窗口底边还剩多少，只补上键盘超出这部分的高度。
     * 用测量而不是从 inset 里推算，是因为 activity 根布局带 `fitsSystemWindows="true"`，
     * 系统栏 inset 很可能已在那一层被消费掉，在 Fragment 里再读会得到 0。
     *
     * ⚠️ 这段行为需要在真机/模拟器上目视确认（见验收清单）。
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            // 本视图底边到窗口底边的距离：底部导航栏 + 系统导航栏
            val distanceBelow = view.rootView.height - (location[1] + view.height)
            view.updatePadding(bottom = (imeBottom - distanceBelow).coerceAtLeast(0))
            insets
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerChat.adapter = null
        _binding = null
    }

    companion object {
        /** DeepSeek 开放平台的 API Key 申请页。 */
        private const val API_KEY_URL = "https://platform.deepseek.com/api_keys"
    }
}

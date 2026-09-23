package com.example.personalledger

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import java.util.Locale
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.personalledger.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    var reportFragment: ReportFragment? = null

    override fun attachBaseContext(newBase: Context) {
        // 只锁定中文文案（本项目的字符串资源全部是中文），**不再**把 fontScale 压成 1.0f：
        // 那样会让系统「字体大小」设置彻底失效，属于无障碍缺陷。
        //
        // 这里改为「尊重系统设置 + 上限 MAX_FONT_SCALE」：
        // 分段式 TabLayout 的 pill 高度依赖硬编码的 `tabIndicatorHeight`，而它必须等于
        // TabLayout 的完整高度（见 styles.xml 里 Widget.PersonalLedger.TabLayout 的说明），
        // 该高度无法随字体自适应；倍数再大就会把 Tab 文字裁掉。
        // 要完全放开缩放，需要先把 Tab 的高度约束重构掉。
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.CHINESE)
            fontScale = fontScale.coerceIn(1.0f, MAX_FONT_SCALE)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lock the entire app to Chinese locale
        Locale.setDefault(Locale.CHINESE)
        enableEdgeToEdge()

        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setTitleTextColor(ThemeColors.of(this, com.google.android.material.R.attr.colorOnSurface, R.color.text_primary))

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }

        binding.bottomNavView.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.navigation_home -> {
                    switchTo(HomeFragment())
                    true
                }

                R.id.navigation_report -> {
                    val fragment = ReportFragment()
                    reportFragment = fragment
                    switchTo(fragment)
                    true
                }

                else -> false
            }
        }
    }

    /** 用轻量淡入淡出（crossfade）替换当前页面，比滑动手势更顺滑。 */
    private fun switchTo(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    companion object {
        /**
         * 允许的最大系统字体缩放倍数。
         *
         * 1.3 是当前布局能承受的上限：分段式 TabLayout 的高度写死在 styles.xml 的
         * `tabIndicatorHeight`（40dp / 50dp）里，且必须等于 TabLayout 的完整高度，
         * 再放大就会裁切 Tab 文字。其余界面（列表项、卡片）都已用 `wrap_content` /
         * `minHeight` 自适应，可以放心跟随系统缩放。
         */
        private const val MAX_FONT_SCALE = 1.3f
    }
}

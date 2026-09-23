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
        // 锁定中文文案（本项目的字符串资源全部是中文），并把 fontScale 固定为 1.0f。
        //
        // 界面字号全部按 1.0× 设计：跟随系统「字体大小」会让首页卡片 / 列表行与底部
        // TabLayout 一起放大，而分段式 TabLayout 的 pill 高度依赖硬编码的
        // `tabIndicatorHeight`（必须等于 TabLayout 完整高度，见 styles.xml 说明），
        // 无法随字体自适应，放大后会出现错位与文字裁切。
        //
        // 2026-09-23：曾短暂改为「尊重系统设置 + 上限 1.3×」，实测大字体下界面明显偏大
        // 并伴随异常，按用户要求回退为固定 1.0×。要重新放开缩放，需先重构 Tab 的高度约束。
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.CHINESE)
            fontScale = 1.0f
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
}

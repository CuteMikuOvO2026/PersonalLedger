package com.example.personalledger

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import java.util.Locale
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.personalledger.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    var reportFragment: ReportFragment? = null

    override fun attachBaseContext(newBase: Context) {
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
        binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.text_primary))

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

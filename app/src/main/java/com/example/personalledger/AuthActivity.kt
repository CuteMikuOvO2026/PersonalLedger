package com.example.personalledger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalledger.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var dataStoreManager: DataStoreManager
    private var isLoginMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStoreManager = DataStoreManager(this)

        lifecycleScope.launch {
            if (dataStoreManager.isLoggedIn()) {
                goToMain()
            }
            isLoginMode = dataStoreManager.hasRegisteredUser()
            updateModeUi()
        }

        binding.btnSubmit.setOnClickListener {
            submit()
        }

        binding.tvSwitchMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateModeUi()
        }
    }

    private fun submit() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (username.length < 3) {
            Toast.makeText(this, "用户名至少3位", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            if (isLoginMode) {
                val ok = dataStoreManager.loginUser(username, password)
                if (ok) {
                    Toast.makeText(this@AuthActivity, "登录成功", Toast.LENGTH_SHORT).show()
                    goToMain()
                } else {
                    Toast.makeText(this@AuthActivity, "账号或密码错误", Toast.LENGTH_SHORT).show()
                }
            } else {
                dataStoreManager.registerUser(username, password)
                Toast.makeText(this@AuthActivity, "注册成功", Toast.LENGTH_SHORT).show()
                goToMain()
            }
        }
    }

    private fun updateModeUi() {
        binding.tvTitle.text = if (isLoginMode) "登录" else "注册"
        binding.btnSubmit.text = if (isLoginMode) "登录" else "注册"
        binding.tvSwitchMode.text = if (isLoginMode) "没有账号？去注册" else "已有账号？去登录"
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

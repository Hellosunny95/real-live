package com.xiguatv.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xiguatv.app.data.XiguaRepository
import com.xiguatv.app.ui.XiguaTvApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val prefs = getSharedPreferences("xigua_tv", MODE_PRIVATE)
        val repository = XiguaRepository { prefs.getString("cookie", "").orEmpty() }

        setContent {
            XiguaTvApp(
                repository = repository,
                readCookie = { prefs.getString("cookie", "").orEmpty() },
                saveCookie = { prefs.edit().putString("cookie", it).apply() }
            )
        }
    }
}

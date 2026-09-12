package com.openzeekr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.openzeekr.app.ui.AppRoot
import com.openzeekr.app.ui.theme.OpenZeekrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = (application as App).deps
        setContent {
            OpenZeekrTheme {
                AppRoot(deps)
            }
        }
    }
}

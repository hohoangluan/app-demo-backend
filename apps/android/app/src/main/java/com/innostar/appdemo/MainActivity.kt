package com.innostar.appdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.innostar.appdemo.ui.overview.OverviewScreen
import com.innostar.appdemo.ui.theme.AppDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppDemoTheme {
                OverviewScreen()
            }
        }
    }
}

package com.youreyes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.youreyes.app.ui.overview.OverviewScreen
import com.youreyes.app.ui.theme.AppDemoTheme

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

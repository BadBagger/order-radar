package com.smithware.orderradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.smithware.orderradar.ui.OrderRadarRoot
import com.smithware.orderradar.ui.theme.OrderRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrderRadarTheme {
                OrderRadarRoot()
            }
        }
    }
}

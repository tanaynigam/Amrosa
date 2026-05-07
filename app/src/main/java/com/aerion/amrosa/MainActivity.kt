package com.aerion.amrosa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aerion.amrosa.navigation.AmrosaNavGraph
import com.aerion.amrosa.ui.theme.AmrosaTheme
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmrosaTheme {
                AmrosaNavGraph()
            }
        }
    }
}

package com.ec3control
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ec3control.ui.Ec3App
import com.ec3control.ui.theme.Ec3Theme
class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { Ec3Theme { Ec3App() } } }
}

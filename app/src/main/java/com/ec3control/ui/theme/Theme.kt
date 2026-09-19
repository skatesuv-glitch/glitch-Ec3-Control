package com.ec3control.ui.theme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val Ec3Colors = darkColorScheme(primary=Color(0xFF27B8F2),secondary=Color(0xFF68D7FF),background=Color(0xFF07131F),surface=Color(0xFF0D1D2B),surfaceVariant=Color(0xFF142A3B),onPrimary=Color(0xFF00131D),onBackground=Color(0xFFF4F8FB),onSurface=Color(0xFFF4F8FB),onSurfaceVariant=Color(0xFFAFC1CF))
@Composable fun Ec3Theme(content:@Composable ()->Unit){ MaterialTheme(colorScheme=Ec3Colors,content=content) }

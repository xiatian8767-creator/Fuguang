package com.example.parallaxwallpaper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.example.parallaxwallpaper.ui.SetupScreen
import com.example.parallaxwallpaper.ui.theme.ParallaxWallpaperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            ParallaxWallpaperTheme(darkTheme = false, dynamicColor = false) {
                SetupScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF6F4FA))
                        .statusBarsPadding()
                        .navigationBarsPadding()
                )
            }
        }
    }
}

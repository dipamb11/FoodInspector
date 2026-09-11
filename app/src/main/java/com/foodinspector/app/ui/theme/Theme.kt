package com.foodinspector.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val FoodInspectorColorScheme = lightColorScheme(
    primary = TealPrimary,
    primaryContainer = TealPrimaryContainer,
    secondary = OrangeSecondary,
    secondaryContainer = OrangeSecondaryContainer,
    tertiary = PurpleTertiary,
    tertiaryContainer = PurpleTertiaryContainer,
    background = BackgroundLight,
    surface = SurfaceLight,
)

@Composable
fun FoodInspectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FoodInspectorColorScheme,
        content = content,
    )
}

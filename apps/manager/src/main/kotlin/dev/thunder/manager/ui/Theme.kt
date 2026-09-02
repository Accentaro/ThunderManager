package dev.thunder.manager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private object Latte {
    val Base = Color(0xFFEFF1F5)
    val Mantle = Color(0xFFE6E9EF)
    val Crust = Color(0xFFDCE0E8)
    val Text = Color(0xFF4C4F69)
    val Subtext0 = Color(0xFF6C6F85)
    val Surface0 = Color(0xFFCCD0DA)
    val Surface1 = Color(0xFFBCC0CC)
    val Surface2 = Color(0xFFACB0BE)
    val Mauve = Color(0xFF8839EF)
    val Yellow = Color(0xFFDF8E1D)
    val Green = Color(0xFF40A02B)
    val Red = Color(0xFFD20F39)
}

private object Mocha {
    val Base = Color(0xFF1E1E2E)
    val Mantle = Color(0xFF181825)
    val Crust = Color(0xFF11111B)
    val Text = Color(0xFFCDD6F4)
    val Subtext0 = Color(0xFFA6ADC8)
    val Surface0 = Color(0xFF313244)
    val Surface1 = Color(0xFF45475A)
    val Surface2 = Color(0xFF585B70)
    val Mauve = Color(0xFFCBA6F7)
    val Yellow = Color(0xFFF9E2AF)
    val Green = Color(0xFFA6E3A1)
    val Red = Color(0xFFF38BA8)
}

private val LightColors = lightColorScheme(
    primary = Latte.Mauve,
    onPrimary = Color.White,
    primaryContainer = Latte.Surface0,
    onPrimaryContainer = Latte.Text,
    secondary = Latte.Yellow,
    onSecondary = Latte.Text,
    secondaryContainer = Latte.Mantle,
    onSecondaryContainer = Latte.Text,
    tertiary = Latte.Green,
    onTertiary = Color.White,
    tertiaryContainer = Latte.Surface0,
    onTertiaryContainer = Latte.Text,
    error = Latte.Red,
    onError = Color.White,
    errorContainer = Latte.Mantle,
    onErrorContainer = Latte.Red,
    background = Latte.Base,
    onBackground = Latte.Text,
    surface = Latte.Base,
    onSurface = Latte.Text,
    surfaceVariant = Latte.Mantle,
    onSurfaceVariant = Latte.Subtext0,
    outline = Latte.Surface2,
    outlineVariant = Latte.Surface1,
    inverseSurface = Latte.Text,
    inverseOnSurface = Latte.Base,
    inversePrimary = Mocha.Mauve,
    scrim = Latte.Crust,
)

private val DarkColors = darkColorScheme(
    primary = Mocha.Mauve,
    onPrimary = Mocha.Crust,
    primaryContainer = Mocha.Surface1,
    onPrimaryContainer = Mocha.Text,
    secondary = Mocha.Yellow,
    onSecondary = Mocha.Crust,
    secondaryContainer = Mocha.Surface0,
    onSecondaryContainer = Mocha.Text,
    tertiary = Mocha.Green,
    onTertiary = Mocha.Crust,
    tertiaryContainer = Mocha.Surface1,
    onTertiaryContainer = Mocha.Text,
    error = Mocha.Red,
    onError = Mocha.Crust,
    errorContainer = Mocha.Surface0,
    onErrorContainer = Mocha.Red,
    background = Mocha.Base,
    onBackground = Mocha.Text,
    surface = Mocha.Base,
    onSurface = Mocha.Text,
    surfaceVariant = Mocha.Surface0,
    onSurfaceVariant = Mocha.Subtext0,
    outline = Mocha.Surface2,
    outlineVariant = Mocha.Surface1,
    inverseSurface = Mocha.Text,
    inverseOnSurface = Mocha.Base,
    inversePrimary = Latte.Mauve,
    scrim = Mocha.Crust,
)

private val ThunderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ThunderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = ThunderShapes,
        content = content,
    )
}

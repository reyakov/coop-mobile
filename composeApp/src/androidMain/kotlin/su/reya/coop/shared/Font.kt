package su.reya.coop.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import coop.composeapp.generated.resources.PaytoneOne_Regular
import coop.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font

@Composable
fun getExpressiveFontFamily() = FontFamily(
    Font(Res.font.PaytoneOne_Regular, FontWeight.Normal)
)
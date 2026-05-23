package su.reya.coop.shared

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.avatar
import org.jetbrains.compose.resources.painterResource

@Composable
fun Avatar(
    picture: String?,
    description: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape = CircleShape
) {
    val placeholder = painterResource(Res.drawable.avatar)

    AsyncImage(
        model = picture,
        contentDescription = description,
        modifier = modifier
            .size(size)
            .clip(shape),
        contentScale = ContentScale.Crop,
        fallback = placeholder,
        error = placeholder,
        placeholder = placeholder
    )
}

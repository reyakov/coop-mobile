package su.reya.coop.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.URL_REGEX
import su.reya.coop.formatAsTime
import su.reya.coop.isImageUrl
import su.reya.coop.removeImageUrls

@Immutable
data class MessageUiModel(
    val id: String,
    val annotatedContent: AnnotatedString,
    val images: List<String>,
    val timestamp: String,
    val isMine: Boolean
)

@Composable
fun rememberMessageUiModel(
    event: UnsignedEvent,
    currentUserPublicKey: PublicKey?,
    contentColor: Color
): MessageUiModel {
    return remember(event, currentUserPublicKey, contentColor) {
        val content = event.content()
        val images = URL_REGEX.findAll(content)
            .map { it.value }
            .filter { it.isImageUrl() }
            .toList()

        val cleanedContent = content.removeImageUrls()

        val annotatedString = buildAnnotatedString {
            var lastIndex = 0
            URL_REGEX.findAll(cleanedContent).forEach { matchResult ->
                append(cleanedContent.substring(lastIndex, matchResult.range.first))
                val url = matchResult.value
                pushLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = contentColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    )
                )
                append(url)
                pop()
                lastIndex = matchResult.range.last + 1
            }
            append(cleanedContent.substring(lastIndex))
        }

        MessageUiModel(
            id = event.id()?.toHex() ?: event.hashCode().toString(),
            annotatedContent = annotatedString,
            images = images,
            timestamp = event.createdAt().formatAsTime(),
            isMine = event.author() == currentUserPublicKey
        )
    }
}

@Composable
fun ChatMessage(
    model: MessageUiModel,
    modifier: Modifier = Modifier,
    onLongClick: (Rect) -> Unit = {}
) {
    var isMessageClicked by remember { mutableStateOf(false) }
    var layoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val bubbleShape = if (model.isMine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }

    val containerColor =
        if (!model.isMine) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer

    val contentColor =
        if (!model.isMine) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = modifier
            .onGloballyPositioned { layoutCoordinates = it }
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (model.isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { isMessageClicked = !isMessageClicked },
                onLongClick = {
                    layoutCoordinates?.let { coords ->
                        onLongClick(coords.boundsInWindow())
                    }
                }
            ),
            horizontalAlignment = if (model.isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (model.annotatedContent.isNotBlank()) {
                Surface(
                    color = containerColor,
                    contentColor = contentColor,
                    shape = bubbleShape,
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Text(
                        text = model.annotatedContent,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            model.images.forEach { imageUrl ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Image from chat",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
            AnimatedVisibility(
                visible = isMessageClicked,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = model.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(
                        if (model.isMine) Alignment.End else Alignment.Start
                    )
                )
            }
        }
    }
}

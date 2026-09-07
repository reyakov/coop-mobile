package su.reya.coop.screens.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import rust.nostr.sdk.EventId
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.LocalConnectivity
import su.reya.coop.LocalSettings
import su.reya.coop.MediaConfig
import su.reya.coop.URL_REGEX
import su.reya.coop.formatAsTime
import su.reya.coop.isImageUrl
import su.reya.coop.removeImageUrls

@Immutable
data class ReactionGroup(
    val emoji: String,
    val authors: List<PublicKey>,
    val containsMe: Boolean
)

@Immutable
data class MessageModel(
    val id: EventId,
    val author: PublicKey,
    val annotatedContent: AnnotatedString,
    val images: List<String>,
    val timestamp: String,
    val isMine: Boolean,
    val replyEventIds: List<EventId>,
    val reactions: List<ReactionGroup> = emptyList()
)

@Composable
fun rememberMessageModel(
    event: UnsignedEvent,
    reactions: List<UnsignedEvent> = emptyList(),
    currentUser: PublicKey? = null
): MessageModel {
    val settings = LocalSettings.current
    val isMobileData = LocalConnectivity.current

    return remember(event, reactions, currentUser, settings, isMobileData) {
        val id = event.ensureId().id()!!
        val isMine = currentUser == event.author()
        val content = event.content()
        val replyEventIds = event.tags().eventIds()

        val showMedia = when (settings.media) {
            MediaConfig.AlwaysEnabled -> true
            MediaConfig.Disabled -> false
            MediaConfig.DisabledForMobileData -> !isMobileData
        }

        val images = if (showMedia) {
            URL_REGEX.findAll(content).map { it.value }.filter { it.isImageUrl() }.toList()
        } else {
            emptyList()
        }
        val cleanedContent = if (showMedia) content.removeImageUrls() else content

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

        val groupedReactions = reactions.groupBy { it.content() }
            .map { (emoji, events) ->
                val authors = events.map { it.author() }
                ReactionGroup(
                    emoji = emoji,
                    authors = authors,
                    containsMe = authors.any { it == currentUser }
                )
            }

        MessageModel(
            id = id,
            author = event.author(),
            annotatedContent = annotatedString,
            images = images,
            timestamp = event.createdAt().formatAsTime(),
            isMine = isMine,
            replyEventIds = replyEventIds,
            reactions = groupedReactions
        )
    }
}

@Composable
fun ChatMessage(
    model: MessageModel,
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Column(
                    modifier = Modifier.padding(
                        bottom = if (model.reactions.isNotEmpty()) 4.dp else 0.dp,
                        end = if (model.reactions.isNotEmpty() && !model.isMine) 8.dp else 0.dp
                    ),
                    horizontalAlignment = if (model.isMine) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (model.annotatedContent.isNotBlank()) {
                        Surface(
                            modifier = Modifier.widthIn(max = 280.dp),
                            color = containerColor,
                            contentColor = contentColor,
                            shape = bubbleShape,
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
                }

                if (model.reactions.isNotEmpty()) {
                    MessageReactions(
                        reactions = model.reactions,
                        modifier = Modifier.offset(y = 12.dp)
                    )
                }
            }

            if (isMessageClicked) {
                Text(
                    text = model.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = if (model.reactions.isNotEmpty()) 8.dp else 0.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageReactions(
    reactions: List<ReactionGroup>,
    modifier: Modifier = Modifier
) {
    val totalCount = reactions.sumOf { it.authors.size }
    val displayEmojis = reactions.take(3).map { it.emoji }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        displayEmojis.forEach { emoji ->
            Surface(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = emoji,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (totalCount > 2) {
            Surface(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = totalCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

package su.reya.coop.screens.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_add_circle
import coop.composeapp.generated.resources.ic_audio
import coop.composeapp.generated.resources.ic_send
import org.jetbrains.compose.resources.painterResource

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onUpload: () -> Unit,
    onMicClick: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        TextField(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Message") },
            leadingIcon = {
                IconButton(onClick = onUpload) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add_circle),
                        contentDescription = "Upload",
                    )
                }
            },
        )
        Spacer(modifier = Modifier.size(8.dp))
        AnimatedContent(
            targetState = value.isNotEmpty(),
            transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "send_mic_transition"
        ) { isNotEmpty ->
            if (isNotEmpty) {
                IconButton(
                    onClick = onSend,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_send),
                        contentDescription = "Send"
                    )
                }
            } else {
                FilledTonalIconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_audio),
                        contentDescription = "Speech to Text"
                    )
                }
            }
        }
    }
}

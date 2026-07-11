package su.reya.coop.shared

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_plus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalSnackbarHostState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileEditor(
    title: String,
    buttonLabel: String,
    initialName: String = "",
    initialBio: String = "",
    initialPicture: Any? = null, // Accepts Uri (picked) or String (current URL)
    isBusy: Boolean = false,
    onBack: () -> Unit,
    onConfirm: (name: String, bio: String, pictureBytes: ByteArray?, contentType: String?) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var name by remember(initialName) { mutableStateOf(initialName) }
    var bio by remember(initialBio) { mutableStateOf(initialBio) }
    var picture by remember(initialPicture) { mutableStateOf(initialPicture) }

    val hasPicture = remember(picture) {
        when (picture) {
            null -> false
            is String -> (picture as CharSequence).isNotBlank()
            else -> true
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picture = uri
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMediumEmphasized) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(MaterialShapes.Pentagon.toShape())
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (hasPicture) {
                        AsyncImage(
                            model = picture,
                            contentDescription = "Profile picture",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_plus),
                                    contentDescription = "Pick avatar",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryFixed
                                )
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "What others should call you?",
                            style = MaterialTheme.typography.titleLargeEmphasized.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                }
                            ),
                            textStyle = MaterialTheme.typography.headlineLargeEmphasized.copy(
                                color = MaterialTheme.colorScheme.tertiaryFixedDim,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiaryContainer),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (name.isEmpty()) {
                                        Text(
                                            "Alice",
                                            style = MaterialTheme.typography.headlineLargeEmphasized.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Your bio (optional)",
                            style = MaterialTheme.typography.titleLargeEmphasized.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        BasicTextField(
                            value = bio,
                            onValueChange = { bio = it },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.primaryFixed,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (bio.isEmpty()) {
                                        Text(
                                            "I love cat",
                                            style = MaterialTheme.typography.headlineLargeEmphasized.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.size(16.dp))
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(ButtonDefaults.MediumContainerHeight),
                        onClick = {
                            scope.launch {
                                try {
                                    val bytes = withContext(ioDispatcher) {
                                        (picture as? Uri)?.let {
                                            context.contentResolver.openInputStream(it)?.readBytes()
                                        }
                                    }
                                    val type =
                                        (picture as? Uri)?.let { context.contentResolver.getType(it) }
                                    onConfirm(name, bio, bytes, type)
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(e.message ?: "Error")
                                }
                            }
                        },
                        enabled = name.isNotBlank() && !isBusy
                    ) {
                        if (isBusy) {
                            LoadingIndicator()
                        } else {
                            Text(
                                text = buttonLabel,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                        }
                    }
                }
            }
        }
    }
}
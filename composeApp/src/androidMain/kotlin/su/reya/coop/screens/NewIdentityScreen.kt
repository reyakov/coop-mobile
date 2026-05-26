package su.reya.coop.screens

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_plus
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalSnackbarHostState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewIdentityScreen(
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: (name: String, bio: String?, picture: Uri?) -> Unit
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        picture = uri
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Create a new identity",
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            )
        },
        content = { innerPadding ->
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
                        if (picture != null) {
                            AsyncImage(
                                model = picture,
                                contentDescription = "Profile picture",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxSize()

                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_plus),
                                        contentDescription = "Pick avatar",
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    color = MaterialTheme.colorScheme.primaryFixed,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
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
                            onClick = {
                                onSave(name, bio, picture)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ButtonDefaults.MediumContainerHeight),
                            enabled = name.isNotBlank() && !isLoading,
                        ) {
                            if (isLoading) {
                                LoadingIndicator()
                            } else {
                                Text(
                                    text = "Continue",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

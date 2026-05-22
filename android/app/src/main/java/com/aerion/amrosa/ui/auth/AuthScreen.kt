package com.aerion.amrosa.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerion.amrosa.AmrosaApplication
import com.aerion.amrosa.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AmrosaApplication
    val viewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(app.container.authRepository)
    )
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val activity = context as Activity

    // ── Navigate away once authenticated ─────────────────────────────────────
    LaunchedEffect(state.authenticatedUserId) {
        if (state.authenticatedUserId != null) onBack()
    }

    // ── Google Sign-In client ─────────────────────────────────────────────────
    val webClientId = stringResource(R.string.google_web_client_id)
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                account.getResult(ApiException::class.java)?.idToken?.let { token ->
                    viewModel.signInWithGoogle(token)
                }
            } catch (_: ApiException) { /* cancelled or failed */ }
        }
    }

    // ── Field state ───────────────────────────────────────────────────────────
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }

    // Reset fields when switching mode
    LaunchedEffect(state.mode) {
        name = ""; email = ""; password = ""; confirmPassword = ""
        passwordVisible = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.phoneStep != PhoneStep.NONE) viewModel.cancelPhone()
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── App heading ───────────────────────────────────────────────────
            Text(
                "Amrita & Ambrosia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(
                "Your personal recipe collection",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Error banner ──────────────────────────────────────────────────
            AnimatedVisibility(visible = state.errorMessage != null) {
                state.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = viewModel::clearError,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Main content (animated between phone steps and main form) ─────
            AnimatedContent(
                targetState = state.phoneStep,
                transitionSpec = {
                    (slideInVertically { it / 4 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 4 } + fadeOut())
                },
                label = "auth_content"
            ) { phoneStep ->
                when (phoneStep) {
                    PhoneStep.ENTERING_NUMBER -> PhoneNumberSection(
                        phoneNumber = phoneNumber,
                        onPhoneChange = { phoneNumber = it },
                        isLoading = state.isLoading,
                        onSend = { viewModel.sendPhoneOtp(phoneNumber, activity) },
                        onCancel = viewModel::cancelPhone
                    )

                    PhoneStep.ENTERING_OTP -> OtpSection(
                        phoneNumber = phoneNumber,
                        otpCode = otpCode,
                        onOtpChange = { otpCode = it },
                        isLoading = state.isLoading,
                        onVerify = { viewModel.verifyPhoneOtp(otpCode) },
                        onResend = { viewModel.sendPhoneOtp(phoneNumber, activity) },
                        onCancel = viewModel::cancelPhone
                    )

                    PhoneStep.NONE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // ── Mode segmented button ─────────────────────────
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                AuthMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = state.mode == mode,
                                        onClick = { viewModel.setMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = AuthMode.entries.size
                                        ),
                                        label = {
                                            Text(
                                                if (mode == AuthMode.SIGN_IN) "Sign In"
                                                else "Create Account"
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // ── Google button ──────────────────────────────────
                            OutlinedButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    googleSignInClient.signOut().addOnCompleteListener {
                                        googleLauncher.launch(googleSignInClient.signInIntent)
                                    }
                                },
                                enabled = !state.isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (state.mode == AuthMode.SIGN_IN) "Continue with Google"
                                    else "Sign up with Google",
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // ── Divider ────────────────────────────────────────
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f))
                                Text(
                                    "  or  ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f))
                            }

                            // ── Email/Password form ────────────────────────────
                            AnimatedContent(
                                targetState = state.mode,
                                transitionSpec = {
                                    (fadeIn()) togetherWith (fadeOut())
                                },
                                label = "mode_form"
                            ) { mode ->
                                Column {
                                    if (mode == AuthMode.SIGN_UP) {
                                        OutlinedTextField(
                                            value = name,
                                            onValueChange = { name = it },
                                            label = { Text("Full name") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Person, contentDescription = null)
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Text,
                                                imeAction = ImeAction.Next
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                            ),
                                            enabled = !state.isLoading,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }

                                    OutlinedTextField(
                                        value = email,
                                        onValueChange = { email = it },
                                        label = { Text("Email") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Email, contentDescription = null)
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Email,
                                            imeAction = ImeAction.Next
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                        ),
                                        enabled = !state.isLoading,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = { password = it },
                                        label = { Text("Password") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Lock, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                                Icon(
                                                    if (passwordVisible) Icons.Default.VisibilityOff
                                                    else Icons.Default.Visibility,
                                                    contentDescription = if (passwordVisible) "Hide" else "Show"
                                                )
                                            }
                                        },
                                        visualTransformation = if (passwordVisible)
                                            VisualTransformation.None
                                        else
                                            PasswordVisualTransformation(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = if (mode == AuthMode.SIGN_UP)
                                                ImeAction.Next else ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                            onDone = {
                                                focusManager.clearFocus()
                                                viewModel.signInWithEmail(email, password)
                                            }
                                        ),
                                        enabled = !state.isLoading,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    if (mode == AuthMode.SIGN_UP) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = confirmPassword,
                                            onValueChange = { confirmPassword = it },
                                            label = { Text("Confirm password") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Lock, contentDescription = null)
                                            },
                                            visualTransformation = if (passwordVisible)
                                                VisualTransformation.None
                                            else
                                                PasswordVisualTransformation(),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Password,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    focusManager.clearFocus()
                                                    viewModel.signUpWithEmail(
                                                        name, email, password, confirmPassword
                                                    )
                                                }
                                            ),
                                            enabled = !state.isLoading,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    // Primary CTA
                                    Button(
                                        onClick = {
                                            focusManager.clearFocus()
                                            if (mode == AuthMode.SIGN_IN) {
                                                viewModel.signInWithEmail(email, password)
                                            } else {
                                                viewModel.signUpWithEmail(
                                                    name, email, password, confirmPassword
                                                )
                                            }
                                        },
                                        enabled = !state.isLoading,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        if (state.isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            if (mode == AuthMode.SIGN_IN) "Sign In"
                                            else "Create Account",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // ── Phone option ──────────────────────────────────
                            TextButton(
                                onClick = viewModel::showPhoneEntry,
                                enabled = !state.isLoading
                            ) {
                                Icon(
                                    Icons.Default.Phone,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (state.mode == AuthMode.SIGN_IN) "Use phone number instead"
                                    else "Sign up with phone number"
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Phone number entry ───────────────────────────────────────────────────────

@Composable
private fun PhoneNumberSection(
    phoneNumber: String,
    onPhoneChange: (String) -> Unit,
    isLoading: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Enter your phone number",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            "We'll send a one-time code to verify your number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneChange,
            label = { Text("Phone number") },
            placeholder = { Text("+1 555 000 0000") },
            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus(); onSend()
            }),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Include your country code, e.g. +1 for US",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { focusManager.clearFocus(); onSend() },
            enabled = !isLoading && phoneNumber.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sending code…")
            } else {
                Text("Send Code", fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(onClick = onCancel, enabled = !isLoading) {
            Text("Use a different method")
        }
    }
}

// ─── OTP entry ────────────────────────────────────────────────────────────────

@Composable
private fun OtpSection(
    phoneNumber: String,
    otpCode: String,
    onOtpChange: (String) -> Unit,
    isLoading: Boolean,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Sms,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Enter the code",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            "We sent a 6-digit code to $phoneNumber",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = otpCode,
            onValueChange = { if (it.length <= 6) onOtpChange(it) },
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus(); onVerify()
            }),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { focusManager.clearFocus(); onVerify() },
            enabled = !isLoading && otpCode.length == 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verifying…")
            } else {
                Text("Verify & Sign In", fontWeight = FontWeight.SemiBold)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onResend, enabled = !isLoading) { Text("Resend code") }
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onCancel, enabled = !isLoading) { Text("Cancel") }
        }
    }
}

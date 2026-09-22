package dev.pranav.applock.features.lockscreen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.PreferencesRepository
import dev.pranav.applock.ui.icons.Fingerprint

/** What the lock screen is currently showing. */
enum class LockUiMode {
    /** The regular PIN / pattern / password entry UI. */
    CREDENTIAL_ENTRY,

    /**
     * "Biometrics first": an opaque backdrop with the app's icon and name. The system
     * biometric prompt is shown on top of it and the credential UI is only revealed when the
     * user explicitly asks for it.
     */
    BIOMETRIC_ONLY
}

/**
 * Full lock screen used by the overlay window hosts (accessibility, usage stats and Shizuku
 * backends). Switches between the credential UI and the biometric backdrop.
 */
@Composable
fun LockScreenContent(
    lockedAppName: String,
    appIcon: ImageBitmap?,
    triggeringPackageName: String?,
    mode: LockUiMode,
    promptActive: Boolean,
    biometricStatus: String?,
    showBiometricButton: Boolean,
    onPinAttempt: (String) -> Boolean,
    onPatternAttempt: (String) -> Boolean,
    onPasswordAttempt: (String) -> Boolean,
    onBiometricAuth: () -> Unit,
    onUseCredential: () -> Unit,
    onClose: () -> Unit
) {
    val appLockRepository = LocalContext.current.appLockRepository()

    when (mode) {
        LockUiMode.BIOMETRIC_ONLY -> BiometricBackdropScreen(
            appName = lockedAppName,
            appIcon = appIcon,
            statusText = biometricStatus,
            promptActive = promptActive,
            onRetry = onBiometricAuth,
            onUseCredential = onUseCredential,
            onClose = onClose
        )

        LockUiMode.CREDENTIAL_ENTRY -> when (appLockRepository.getLockType()) {
            PreferencesRepository.LOCK_TYPE_PATTERN -> PatternLockScreen(
                fromMainActivity = false,
                showCloseButton = true,
                onClose = onClose,
                lockedAppName = lockedAppName,
                triggeringPackageName = triggeringPackageName,
                onPatternAttempt = onPatternAttempt,
                onBiometricAuth = if (showBiometricButton) onBiometricAuth else null,
                autoPromptBiometric = false
            )

            PreferencesRepository.LOCK_TYPE_PASSWORD -> AlphanumericPasswordOverlayScreen(
                showBiometricButton = showBiometricButton,
                fromMainActivity = false,
                showCloseButton = true,
                onClose = onClose,
                lockedAppName = lockedAppName,
                triggeringPackageName = triggeringPackageName,
                onAuthSuccess = {},
                onBiometricAuth = onBiometricAuth,
                onPasswordAttempt = onPasswordAttempt
            )

            else -> PinPasswordOverlayScreen(
                showBiometricButton = showBiometricButton,
                fromMainActivity = false,
                showCloseButton = true,
                onClose = onClose,
                lockedAppName = lockedAppName,
                triggeringPackageName = triggeringPackageName,
                onAuthSuccess = {},
                onBiometricAuth = onBiometricAuth,
                onPinAttempt = onPinAttempt
            )
        }
    }
}

/**
 * Opaque screen shown behind the system biometric prompt so the locked app's content is
 * never visible before authentication succeeds. While [promptActive] is true the action
 * buttons are hidden because the system prompt is covering the screen anyway.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BiometricBackdropScreen(
    appName: String,
    appIcon: ImageBitmap?,
    statusText: String?,
    promptActive: Boolean,
    onRetry: () -> Unit,
    onUseCredential: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 8.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_button_cd),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(24.dp))
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Text(
                    text = stringResource(R.string.unlock_app_title, appName),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusText ?: stringResource(R.string.confirm_biometric_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusText != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                if (!promptActive) {
                    FilledTonalIconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(72.dp),
                        shape = RoundedCornerShape(40),
                    ) {
                        Icon(
                            imageVector = Fingerprint,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            contentDescription = stringResource(R.string.biometric_authentication_cd),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = onUseCredential) {
                        Text(stringResource(R.string.use_pin_button))
                    }
                }
            }
        }
    }
}

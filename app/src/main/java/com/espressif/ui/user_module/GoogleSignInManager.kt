// Copyright 2026 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ui.user_module

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.espressif.rainmaker.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Displays the Google account selection UI provided by Google (through Credential Manager) and
 * hands back the Google ID token of the account the user picked.
 *
 * This replaces opening the RainMaker hosted authorize URL in a browser. The returned ID token is
 * exchanged for RainMaker tokens by [com.espressif.cloudapi.ApiManager.loginWithGoogleIdToken].
 *
 * An activity is required because the account picker is shown on top of the caller.
 */
class GoogleSignInManager(private val activity: Activity) {

    companion object {

        private const val TAG = "GoogleSignInManager"

        /**
         * Returns true when a Google OAuth web client id has been configured for this build.
         * Without it Credential Manager cannot request a Google ID token.
         */
        private fun isConfigured(): Boolean {
            val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
            return !TextUtils.isEmpty(clientId) && !clientId.startsWith("your_")
        }

        /**
         * Clears the credential state held by the credential providers for this app, so that the
         * next sign-in asks the user to pick an account again instead of silently reusing the
         * previously selected one.
         *
         * Must be called whenever the user session ends (logout or account deletion). Shows no UI,
         * runs asynchronously and never throws, so it is safe to call from any thread.
         */
        @JvmStatic
        fun signOut(context: Context) {

            val appContext = context.applicationContext

            try {
                CredentialManager.create(appContext).clearCredentialStateAsync(
                    ClearCredentialStateRequest(ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE),
                    null,
                    ContextCompat.getMainExecutor(appContext),
                    object : CredentialManagerCallback<Void?, ClearCredentialException> {

                        override fun onResult(result: Void?) {
                            Log.d(TAG, "Cleared Google credential state")
                        }

                        override fun onError(e: ClearCredentialException) {
                            // Nothing the user can do about it, the next sign-in still shows the
                            // account chooser because sign-in never auto-selects an account.
                            Log.e(TAG, "Failed to clear Google credential state", e)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request clearing of Google credential state", e)
            }
        }
    }

    interface GoogleSignInListener {

        /** The user picked an account and Google issued an ID token for it. */
        fun onSignInSuccess(idToken: String)

        /** The user dismissed the account picker. Nothing to report to the user. */
        fun onSignInCancelled()

        /** No Google account is available on the device. */
        fun onNoGoogleAccount()

        /**
         * Credential Manager cannot serve the request on this device, typically because Google
         * Play services is missing or too old. Callers are expected to fall back to the hosted
         * (browser) login flow.
         */
        fun onSignInUnavailable()

        fun onSignInFailure(exception: Exception)
    }

    private val credentialManager = CredentialManager.create(activity)

    /**
     * Shows the Google account picker.
     *
     * @param scope lifecycle scope of the caller, so the request is cancelled along with the screen.
     */
    fun signIn(scope: CoroutineScope, listener: GoogleSignInListener) {

        if (!isConfigured()) {
            Log.e(TAG, "Google web client id is not configured for this build")
            listener.onSignInUnavailable()
            return
        }

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
            )
            .build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                handleCredential(result.credential, listener)
            } catch (e: GetCredentialCancellationException) {
                // Google reports a failed request the same way as a dismissed picker, so log the
                // details. A sign-in that failed instead of being dismissed is usually explained by
                // a warning from the Auth tag, such as this app not being registered to use OAuth.
                Log.d(TAG, "Google account selection cancelled : " + e.errorMessage)
                listener.onSignInCancelled()
            } catch (e: NoCredentialException) {
                Log.e(TAG, "No Google account available on this device", e)
                listener.onNoGoogleAccount()
            } catch (e: GetCredentialProviderConfigurationException) {
                Log.e(TAG, "Credential Manager is not usable on this device", e)
                listener.onSignInUnavailable()
            } catch (e: GetCredentialUnsupportedException) {
                Log.e(TAG, "Credential Manager is not supported on this device", e)
                listener.onSignInUnavailable()
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Failed to get Google credential", e)
                listener.onSignInFailure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected failure while getting Google credential", e)
                listener.onSignInFailure(e)
            }
        }
    }

    private fun handleCredential(credential: Credential, listener: GoogleSignInListener) {

        if (credential !is CustomCredential
            || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Log.e(TAG, "Unexpected credential type : " + credential.type)
            listener.onSignInFailure(
                IllegalStateException("Unexpected credential type : " + credential.type)
            )
            return
        }

        try {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            Log.d(TAG, "Received Google ID token for the selected account")
            listener.onSignInSuccess(googleIdTokenCredential.idToken)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Failed to parse Google ID token", e)
            listener.onSignInFailure(e)
        }
    }
}

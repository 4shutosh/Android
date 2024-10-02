/*
 * Copyright (c) 2024 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.autofill.impl.configuration.integration.modern.listener.password

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.autofill.api.AutofillWebMessageRequest
import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.api.passwordgeneration.AutomaticSavedLoginsMonitor
import com.duckduckgo.autofill.impl.InternalAutofillCapabilityChecker
import com.duckduckgo.autofill.impl.configuration.integration.modern.listener.AutofillWebMessageListener
import com.duckduckgo.autofill.impl.domain.javascript.JavascriptCredentials
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillRequestParser
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillStoreFormDataRequest
import com.duckduckgo.autofill.impl.store.InternalAutofillStore
import com.duckduckgo.autofill.impl.store.NeverSavedSiteRepository
import com.duckduckgo.autofill.impl.systemautofill.SystemAutofillServiceSuppressor
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.DeleteAutoLogin
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.DiscardAutoLoginId
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.PromptToSave
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.UpdateSavedAutoLogin
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.AutogeneratedPasswordEventResolver
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.FragmentScope
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@SingleInstanceIn(FragmentScope::class)
@ContributesMultibinding(FragmentScope::class)
@SuppressLint("RequiresFeature")
class WebMessageListenerStoreFormData @Inject constructor(
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val autofillCapabilityChecker: InternalAutofillCapabilityChecker,
    private val neverSavedSiteRepository: NeverSavedSiteRepository,
    private val requestParser: AutofillRequestParser,
    private val autoSavedLoginsMonitor: AutomaticSavedLoginsMonitor,
    private val autofillStore: InternalAutofillStore,
    private val passwordEventResolver: AutogeneratedPasswordEventResolver,
    private val systemAutofillServiceSuppressor: SystemAutofillServiceSuppressor,
) : AutofillWebMessageListener() {

    override val key: String
        get() = "ddgStoreFormData"

    override fun onPostMessage(
        webView: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        kotlin.runCatching {
            // important to call suppressor as soon as possible
            systemAutofillServiceSuppressor.suppressAutofill(webView)

            val originalUrl: String? = webView.url

            appCoroutineScope.launch(dispatchers.io()) {
                val requestOrigin = sourceOrigin.toString()
                val requestId = storeReply(reply)
                storeFormData(
                    message.data.toString(),
                    AutofillWebMessageRequest(requestOrigin = requestOrigin, originalPageUrl = originalUrl, requestId = requestId),
                )
            }
        }.onFailure {
            Timber.e(it, "Error while processing autofill web message for %s", key)
        }
    }

    private suspend fun storeFormData(
        data: String,
        autofillWebMessageRequest: AutofillWebMessageRequest,
    ) {
        Timber.i("storeFormData called, credentials provided to be persisted")

        if (autofillWebMessageRequest.originalPageUrl == null) return

        if (!autofillCapabilityChecker.canSaveCredentialsFromWebView(autofillWebMessageRequest.requestOrigin)) {
            Timber.v("BrowserAutofill: storeFormData called but feature is disabled")
            return
        }

        if (neverSavedSiteRepository.isInNeverSaveList(autofillWebMessageRequest.requestOrigin)) {
            Timber.v("BrowserAutofill: storeFormData called but site is in never save list")
            return
        }

        val parseResult = requestParser.parseStoreFormDataRequest(data)
        val request = parseResult.getOrElse {
            Timber.w(it, "Unable to parse storeFormData request")
            return
        }

        if (!request.isValid()) {
            Timber.w("Invalid data from storeFormData")
            return
        }

        val jsCredentials = JavascriptCredentials(request.credentials!!.username, request.credentials.password)
        val credentials = jsCredentials.asLoginCredentials(autofillWebMessageRequest.requestOrigin)

        val autologinId = autoSavedLoginsMonitor.getAutoSavedLoginId(tabId)
        Timber.i("Autogenerated? %s, Previous autostored login ID: %s", request.credentials.autogenerated, autologinId)
        val autosavedLogin = autologinId?.let { autofillStore.getCredentialsWithId(it) }

        val autogenerated = request.credentials.autogenerated
        val actions = passwordEventResolver.decideActions(autosavedLogin, autogenerated)
        processStoreFormDataActions(actions, autofillWebMessageRequest, credentials)
    }

    private fun isUpdateRequired(
        existingCredentials: LoginCredentials,
        credentials: LoginCredentials,
    ): Boolean {
        return existingCredentials.username != credentials.username || existingCredentials.password != credentials.password
    }

    private fun AutofillStoreFormDataRequest?.isValid(): Boolean {
        if (this == null || credentials == null) return false
        return !(credentials.username.isNullOrBlank() && credentials.password.isNullOrBlank())
    }

    private suspend fun processStoreFormDataActions(
        actions: List<Actions>,
        autofillWebMessageRequest: AutofillWebMessageRequest,
        credentials: LoginCredentials,
    ) {
        Timber.d("%d actions to take: %s", actions.size, actions.joinToString())
        actions.forEach {
            when (it) {
                is DeleteAutoLogin -> {
                    autofillStore.deleteCredentials(it.autologinId)
                }

                is DiscardAutoLoginId -> {
                    autoSavedLoginsMonitor.clearAutoSavedLoginId(tabId)
                }

                is PromptToSave -> {
                    callback.onCredentialsAvailableToSave(autofillWebMessageRequest, credentials)
                }

                is UpdateSavedAutoLogin -> {
                    autofillStore.getCredentialsWithId(it.autologinId)?.let { existingCredentials ->
                        if (isUpdateRequired(existingCredentials, credentials)) {
                            Timber.v("Update required as not identical to what is already stored. id=%s", it.autologinId)
                            val toSave = existingCredentials.copy(username = credentials.username, password = credentials.password)
                            autofillStore.updateCredentials(toSave)?.let { savedCredentials ->
                                callback.onCredentialsSaved(savedCredentials)
                            }
                        } else {
                            Timber.v("Update not required as identical to what is already stored. id=%s", it.autologinId)
                            callback.onCredentialsSaved(existingCredentials)
                        }
                    }
                }
            }
        }
    }

    private fun JavascriptCredentials.asLoginCredentials(
        url: String,
    ): LoginCredentials {
        return LoginCredentials(
            id = null,
            domain = url,
            username = username,
            password = password,
            domainTitle = null,
        )
    }
}
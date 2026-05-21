package com.example

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class ApiKeyService {

    private val attrs = CredentialAttributes(
        generateServiceName("StructuredRefactoringAgent", "AnthropicApiKey")
    )

    fun getKey(): String? = PasswordSafe.instance.getPassword(attrs)

    fun setKey(key: String) {
        PasswordSafe.instance.set(attrs, Credentials("anthropic", key))
    }

    fun clearKey() {
        PasswordSafe.instance.set(attrs, null)
    }

    companion object {
        fun instance(): ApiKeyService =
            ApplicationManager.getApplication().getService(ApiKeyService::class.java)
    }
}

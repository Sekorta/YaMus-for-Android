package ru.yamus.app.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.models.AccountInfo

object AccountStore {
    @Volatile
    var account: AccountInfo? = null
        private set

    val uid: Long
        get() = account?.uid ?: TokenStorage.uid

    val isLoggedIn: Boolean
        get() = !TokenStorage.token.isNullOrEmpty()

    suspend fun load(force: Boolean = false): AccountInfo? {
        if (!force && account != null) return account
        return try {
            val result = withContext(Dispatchers.IO) {
                ApiClient.api.getAccountAbout().result
            }
            if (result != null) {
                account = result
                if (result.uid > 0) TokenStorage.uid = result.uid
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        account = null
    }
}

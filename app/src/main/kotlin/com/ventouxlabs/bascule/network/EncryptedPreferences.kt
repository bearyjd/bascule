package com.ventouxlabs.bascule.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * The one construction path for every encrypted store in this app
 * ([EncryptedAuthTokenStore], [EncryptedSessionCookieStore],
 * [com.ventouxlabs.bascule.ble.session.EncryptedConsentStore],
 * [com.ventouxlabs.bascule.data.EncryptedScaleProfileStore]).
 *
 * Both `MasterKey.Builder.build()` and `EncryptedSharedPreferences.create()`
 * throw when the Keystore entry backing the master key has been invalidated or
 * the encrypted file is corrupt. Every caller is a `by lazy` that
 * `BasculeApplication.onCreate` dereferences inside a coroutine, so an
 * unguarded throw here is an uncatchable crash at *every* launch, with no route
 * to a reset control in the UI.
 *
 * Recovery drops the unreadable state and rebuilds: the user re-authenticates
 * and re-registers the scale, which is a far better outcome than an app that
 * cannot start. A second failure is rethrown rather than falling back to plain
 * [SharedPreferences] — silently downgrading credential storage to plaintext
 * would be worse than the crash.
 */
internal fun encryptedPreferences(context: Context, fileName: String): SharedPreferences =
    runCatching { buildEncryptedPreferences(context, fileName) }
        .getOrElse {
            discardUnreadableState(context, fileName)
            buildEncryptedPreferences(context, fileName)
        }

private fun buildEncryptedPreferences(context: Context, fileName: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/**
 * Deletes both halves of the unreadable pair. The master key is shared by every
 * store, so clearing it here cascades: the remaining stores hit this same path
 * on their next construction and reset themselves, which is what a rotated or
 * invalidated Keystore entry requires anyway.
 */
private fun discardUnreadableState(context: Context, fileName: String) {
    runCatching { context.deleteSharedPreferences(fileName) }
    runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

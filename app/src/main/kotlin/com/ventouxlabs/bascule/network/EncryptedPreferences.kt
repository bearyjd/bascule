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
 * Recovery therefore has to happen here, but it escalates rather than opening at
 * its most destructive step — see [buildWithRecovery]. A failure that survives
 * every rung is rethrown rather than falling back to plain [SharedPreferences]:
 * silently downgrading credential storage to plaintext would be worse than the
 * crash.
 */
internal fun encryptedPreferences(context: Context, fileName: String): SharedPreferences =
    buildWithRecovery(
        build = { buildEncryptedPreferences(context, fileName) },
        deleteFile = { context.deleteSharedPreferences(fileName) },
        deleteMasterKey = {
            KeyStore.getInstance(ANDROID_KEYSTORE)
                .apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        },
    )

/**
 * Retries [build] through escalating recovery, discarding at each rung the least
 * state that could still plausibly be at fault.
 *
 * The failure modes cannot be told apart by exception type: Tink's
 * `AndroidKeysetManager` catches the Keystore's `GeneralSecurityException` and
 * `ProviderException` and re-reads the keyset in cleartext, so an invalidated
 * master key and a corrupt keyset both surface as the same `IOException` or
 * `GeneralSecurityException` that `EncryptedSharedPreferences.create` declares.
 * Escalation stands in for a classification that cannot be made.
 *
 * [deleteMasterKey] is last because that key is shared by every store, so
 * clearing it cascades — the other stores hit this same path on their next
 * construction and reset themselves. That is the right answer to a rotated or
 * invalidated Keystore entry and the wrong one to a single store's file going
 * bad, which [deleteFile] already covers.
 *
 * Both discards are themselves best-effort: one that throws must not strand the
 * caller on a rung short of the one that would have worked.
 */
internal fun <T> buildWithRecovery(build: () -> T, deleteFile: () -> Unit, deleteMasterKey: () -> Unit): T {
    runCatching(build).onSuccess { return it }
    // A locked device, direct-boot state, or a busy Keymaster fails the same
    // call that permanent invalidation does. One retry separates the transient
    // case out before anything is destroyed for it.
    runCatching(build).onSuccess { return it }
    runCatching(deleteFile)
    runCatching(build).onSuccess { return it }
    runCatching(deleteMasterKey)
    return build()
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

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

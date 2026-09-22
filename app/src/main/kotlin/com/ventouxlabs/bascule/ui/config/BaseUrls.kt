package com.ventouxlabs.bascule.ui.config

import java.net.URI
import java.net.URISyntaxException

/**
 * Base-URL validation and the host comparison shared by
 * [com.ventouxlabs.bascule.ui.ConfigViewModel.saveBaseUrl] and
 * [SettingsBackupCoordinator.importSettings] — both gate a drain on whether a
 * new URL keeps the host already configured.
 */
internal object BaseUrls {

    /**
     * The host *and port* — hostname alone would treat two different
     * deployments on the same domain (a staging and a production instance
     * distinguished only by port) as "the same server" and drain the
     * backlog to whichever one the import happened to point at. No
     * explicit port means the URL's default for its scheme, so a bare
     * `https://weight.example.com` and `https://weight.example.com:443`
     * still compare equal. Null for a blank or unparseable URL — two of
     * those must never compare equal.
     */
    fun hostOf(url: String?): String? = url
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it) }.getOrNull() }
        ?.takeIf { it.host != null }
        ?.let { uri -> "${uri.host.lowercase()}:${if (uri.port != -1) uri.port else defaultPortFor(uri.scheme)}" }

    private fun defaultPortFor(scheme: String?): Int =
        if (scheme.equals("http", ignoreCase = true)) DEFAULT_HTTP_PORT else DEFAULT_HTTPS_PORT

    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443

    fun validateBaseUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return "Not a valid URL"
        }
        // https only: the manifest declares no cleartext-traffic policy,
        // so on API 28+ a saved http:// URL would validate fine here and
        // then fail at request time with no way for the user to tell why.
        if (uri.scheme != "https") return "URL must start with https://"
        if (uri.host.isNullOrBlank()) return "URL must include a host"
        // VitalForgeHttpClient.resolve() builds request URLs by string
        // concatenation (baseUrl + path), not URI resolution — a query or
        // fragment here silently becomes part of the request path instead
        // of being replaced by it, so every request would go to the host
        // root rather than the intended API path.
        if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) {
            return "URL must not include a query string or fragment"
        }
        return null
    }
}

package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.OnDeviceFailureKind
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object OnDeviceUserMessages {
    fun chooseModel() = "Choose an On-Device model before continuing."

    fun downloadModelFirst() = "Download an On-Device model before continuing."

    fun modelStillDownloading() = "This model is still being prepared."

    fun modelNotReady() = "This model is not ready yet."

    fun missingModelFile() = "The downloaded model file is missing. Download it again."

    fun runtimeUnavailable() = "The on-device model couldn't respond right now."

    fun importSuccess() = "The model was imported and is ready to use."

    fun importFailure(error: Throwable?): String = when {
        error.isNoNetworkIssue() -> "Couldn't import the model right now. Check your internet connection or local file access."
        error.isReadFailure() -> "Couldn't read the selected model file."
        else -> "Couldn't import this model file."
    }

    fun downloadFailure(error: Throwable?): String = when {
        error.isNoNetworkIssue() -> "Couldn't download this model right now. Check your internet connection."
        error.httpStatusCode()?.let { it == 401 || it == 403 } == true ->
            "Couldn't download this model right now. Access to the file is restricted."
        error.httpStatusCode() == 404 ->
            "Couldn't download this model right now. The file is unavailable."
        error.httpStatusCode() != null ->
            "Couldn't download this model right now. The file source is unavailable."
        else -> "Couldn't download this model right now."
    }

    fun cancelDownloadFailure() = "Couldn't cancel the download right now."

    fun refreshCatalogFailure(error: Throwable?): String = when {
        error.isNoNetworkIssue() -> "Couldn't refresh models right now. Check your internet connection."
        error.httpStatusCode() == 404 -> "Couldn't refresh models right now. The catalog is unavailable."
        error.httpStatusCode() != null -> "Couldn't refresh models right now. The catalog couldn't be reached."
        error.isParseFailure() -> "Couldn't refresh models right now. The catalog data is invalid."
        else -> "Couldn't refresh models right now."
    }

    fun cachedCatalogFallback() = "Showing your saved models list."

    fun validationMessage(kind: OnDeviceFailureKind, fallback: String? = null): String = when (kind) {
        OnDeviceFailureKind.INVALID_GGUF -> "This model file is invalid or damaged."
        OnDeviceFailureKind.RUNTIME_INCOMPATIBLE -> "This model can't be used on this device."
        OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE -> missingModelFile()
        OnDeviceFailureKind.DOWNLOAD -> "Couldn't download this model right now."
        OnDeviceFailureKind.INTERNAL_RUNTIME_ERROR -> runtimeUnavailable()
        OnDeviceFailureKind.NONE -> fallback?.takeIf { it.isNotBlank() } ?: modelNotReady()
    }

    fun shortStatus(kind: OnDeviceFailureKind): String = when (kind) {
        OnDeviceFailureKind.RUNTIME_INCOMPATIBLE -> "Not supported on this device"
        OnDeviceFailureKind.INVALID_GGUF -> "Invalid model file"
        OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE -> "Missing model file"
        else -> "Download failed"
    }
}

private fun Throwable?.isNoNetworkIssue(): Boolean {
    val root = this.rootCause()
    val message = root.message.orEmpty()
    return root is UnknownHostException ||
        root is SocketTimeoutException ||
        root is ConnectException ||
        root is SSLException ||
        message.contains("unable to resolve host", ignoreCase = true) ||
        message.contains("failed to connect", ignoreCase = true) ||
        message.contains("timeout", ignoreCase = true) ||
        message.contains("network is unreachable", ignoreCase = true)
}

private fun Throwable?.isReadFailure(): Boolean {
    val root = this.rootCause()
    return root is FileNotFoundException ||
        root.message.orEmpty().contains("read", ignoreCase = true)
}

private fun Throwable?.isParseFailure(): Boolean =
    this.rootCause().message.orEmpty().contains("parse", ignoreCase = true)

private fun Throwable?.httpStatusCode(): Int? {
    val message = this.rootCause().message.orEmpty()
    val match = Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE).find(message) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()
}

private fun Throwable?.rootCause(): Throwable {
    var current = this ?: return IllegalStateException()
    while (current.cause != null && current.cause !== current) {
        current = current.cause!!
    }
    return current
}

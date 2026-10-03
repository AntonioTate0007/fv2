package com.thumbshade.app.ai

import android.content.Context
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gemini Nano through Android AICore (ML Kit GenAI summarisation). The model runs inside the
 * phone's own AI service; text never leaves the device. Only some phones have it, so every call
 * falls back quietly to the built-in summaries.
 */
object Nano {
    enum class Status(val label: String) {
        UNKNOWN("Checking…"),
        AVAILABLE("Ready on this phone"),
        DOWNLOADABLE("Supported: the model can be downloaded by Android"),
        DOWNLOADING("Android is downloading the model…"),
        UNAVAILABLE("Not available on this phone; using built-in summaries"),
    }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status
    @Volatile private var client: Summarizer? = null

    private fun client(context: Context): Summarizer = client ?: Summarization.getClient(
        SummarizerOptions.builder(context.applicationContext)
            .setInputType(SummarizerOptions.InputType.CONVERSATION)
            .setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
            .setLanguage(SummarizerOptions.Language.ENGLISH)
            .build()
    ).also { client = it }

    /** Blocking; call off the main thread. */
    fun check(context: Context): Status {
        val s = runCatching {
            when (client(context).checkFeatureStatus().get()) {
                FeatureStatus.AVAILABLE -> Status.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
                else -> Status.UNAVAILABLE
            }
        }.getOrDefault(Status.UNAVAILABLE)
        _status.value = s
        return s
    }

    /** Asks Android to fetch the model (AICore does the download, not this app). */
    fun download(context: Context) {
        runCatching {
            _status.value = Status.DOWNLOADING
            client(context).downloadFeature(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {}
                override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                override fun onDownloadCompleted() { _status.value = Status.AVAILABLE }
                override fun onDownloadFailed(e: GenAiException) { _status.value = Status.UNAVAILABLE }
            })
        }.onFailure { _status.value = Status.UNAVAILABLE }
    }

    /** A one-line summary, or null when Nano isn't ready. Blocking; call off the main thread. */
    fun summarize(context: Context, text: String): String? {
        if (_status.value == Status.UNKNOWN) check(context)
        if (_status.value != Status.AVAILABLE || text.isBlank()) return null
        return runCatching {
            client(context).runInference(SummarizationRequest.builder(text).build()).get().summary
        }.getOrNull()?.trim()?.removePrefix("•")?.removePrefix("*")?.trim()?.takeIf { it.isNotBlank() }
    }
}

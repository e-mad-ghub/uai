package com.example.uai.ai

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

data class WebGroundingSource(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceQuery: String? = null
)

data class WebGroundingFact(
    val label: String,
    val value: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val sourceQuery: String? = null
)

data class WebGroundingResult(
    val query: String,
    val sources: List<WebGroundingSource>,
    val facts: List<WebGroundingFact> = emptyList()
) {
    fun toPromptBlock(): String = buildString {
        appendLine("<web_search_context>")
        appendLine("Fresh external sources for this turn.")
        appendLine("Query: $query")
        if (facts.isNotEmpty()) {
            appendLine("Resolved fresh facts:")
            facts.forEachIndexed { index, fact ->
                appendLine("${index + 1}. ${fact.label}: ${fact.value}")
                appendLine("Source: ${fact.sourceTitle}")
                appendLine("URL: ${fact.sourceUrl}")
                appendLine()
            }
        }
        val groupedSources = sources.groupBy { it.sourceQuery ?: query }
        if (groupedSources.isNotEmpty() && groupedSources.size > 1) {
            appendLine("Fresh search results by query:")
            groupedSources.forEach { (groupQuery, querySources) ->
                appendLine("Search: $groupQuery")
                querySources.forEachIndexed { index, source ->
                    appendLine("${index + 1}. ${source.title}")
                    appendLine("URL: ${source.url}")
                    if (source.snippet.isNotBlank()) {
                        appendLine("Snippet: ${source.snippet}")
                    }
                    appendLine()
                }
            }
        } else if (groupedSources.isNotEmpty()) {
            appendLine("Fresh search results:")
            sources.forEachIndexed { index, source ->
                appendLine("${index + 1}. ${source.title}")
                appendLine("URL: ${source.url}")
                if (source.snippet.isNotBlank()) {
                    appendLine("Snippet: ${source.snippet}")
                }
                appendLine()
            }
        }
        appendLine(
            "Use these resolved facts and search results only as fresh external context. If you rely on them, " +
                "cite the source title or domain naturally in your answer. Never mention hidden context, " +
                "provided context, shared context, packaged search results, or internal system messages. " +
                "Answer naturally as an assistant with cited sources only."
        )
        appendLine("</web_search_context>")
    }.trim()
}

data class PreparedGroundedMessages(
    val messages: List<ChatMessage>,
    val grounding: WebGroundingResult?
)

interface WebSearchProvider {
    suspend fun search(query: String, maxResults: Int = 5): List<WebGroundingSource>
}

private val webGroundingContextRegex =
    Regex("""(?s)<web_search_context>.*?</web_search_context>\s*""")
private val subjectListSplitRegex =
    Regex("""\s*(?:,|/|&|\band\b|\bvs\b)\s*""", RegexOption.IGNORE_CASE)
private const val WEB_GROUNDING_TAG = "UAI_WEB"
private val stockTickerStopWords = setOf(
    "USD", "US", "NYSE", "NASDAQ", "OTC", "ETF", "CEO", "NEWS", "AI"
)

private fun logWebGroundingDebug(message: String) {
    try {
        Log.d(WEB_GROUNDING_TAG, message)
    } catch (_: RuntimeException) {
        // JVM unit tests do not mock android.util.Log by default.
    }
}

internal fun stripQuotedReplyContext(raw: String): String {
    return raw
        .lineSequence()
        .filterNot { it.trimStart().startsWith("> ") }
        .joinToString("\n")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun stripInjectedWebGroundingContext(raw: String): String {
    return webGroundingContextRegex.replace(raw, "").trim()
}

internal fun sanitizeGroundedAssistantResponse(raw: String): String {
    if (raw.isBlank()) return raw

    var sanitized = raw
    val sentenceLevelReplacements = listOf(
        Regex("""(?i)\b(?:it|that|this|the price|the stock price)\s+is\s+not\s+(?:available|listed)\s+(?:in|within)\s+the\s+(?:shared|provided)\s+(?:internet result\s+)?context\b[^\n.?!]*[.?!]?""") to
            "I couldn't verify that from the sources I checked.",
        Regex("""(?i)\b(?:it|that|this|the price|the stock price)\s+is\s+not\s+(?:available|listed)\s+in\s+the\s+provided\s+search\s+results\b[^\n.?!]*[.?!]?""") to
            "I couldn't verify that from the sources I checked.",
        Regex("""(?i)\bi\s+(?:can(?:not|'t)|could(?: not|n't))\s+(?:find|verify)\b[^\n.?!]*\b(?:provided|shared)\s+(?:search\s+results|context)\b[^\n.?!]*[.?!]?""") to
            "I couldn't verify that from the sources I checked."
    )
    sentenceLevelReplacements.forEach { (pattern, replacement) ->
        sanitized = sanitized.replace(pattern, replacement)
    }

    val phraseLevelReplacements = listOf(
        Regex("""(?i)\bshared internet result context\b""") to "sources I checked",
        Regex("""(?i)\bshared search results\b""") to "sources I checked",
        Regex("""(?i)\bprovided search results\b""") to "sources I checked",
        Regex("""(?i)\bshared context\b""") to "sources I checked",
        Regex("""(?i)\bprovided context\b""") to "sources I checked",
        Regex("""(?i)\bthe context provided\b""") to "the sources I checked",
        Regex("""(?i)\bbased on the (?:shared|provided) (?:search results|context)\b""") to "Based on the sources I checked",
        Regex("""(?i)\bwithin the (?:shared|provided) (?:search results|context)\b""") to "from the sources I checked"
    )
    phraseLevelReplacements.forEach { (pattern, replacement) ->
        sanitized = sanitized.replace(pattern, replacement)
    }

    sanitized = sanitized
        .replace(Regex("""\n{3,}"""), "\n\n")
        .replace(Regex("""\s+([.,?!])"""), "$1")
        .trim()

    return sanitized
}

internal fun shouldUseWebGrounding(userText: String): Boolean {
    val normalized = stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userText)
    ).lowercase()
    if (normalized.isBlank()) return false

    val explicitSignals = listOf(
        "search ",
        "look up",
        "lookup",
        "google ",
        "online",
        "internet",
        "web search",
        "find online",
        "check online"
    )
    if (explicitSignals.any { normalized.contains(it) }) {
        return true
    }

    val freshnessSignals = listOf(
        "latest",
        "current",
        "today",
        "recent",
        "news",
        "up to date",
        "price",
        "stock",
        "release",
        "version",
        "updated",
        "update on",
        "what happened",
        "who is currently",
        "who's currently"
    )
    return freshnessSignals.any { normalized.contains(it) }
}

internal fun previousComparableUserText(messages: List<ChatMessage>): String? {
    val userMessages = messages.filter { it.role == "user" }
    if (userMessages.size < 2) return null
    return stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userMessages[userMessages.lastIndex - 1].content)
    ).takeIf { it.isNotBlank() }
}

private fun splitSubjectCandidates(raw: String): List<String> {
    return raw
        .split(subjectListSplitRegex)
        .map { part ->
            part
                .replace(
                    Regex(
                        """(?i)\b(the|latest|current|today|recent|now|right now|price|prices|stock|stocks|share|shares|quote|quotes|please|for me|of|for)\b"""
                    ),
                    " "
                )
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '?', '.', '!', ',', ':', ';')
        }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun extractStockSubjects(userText: String): List<String> {
    val normalized = stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userText)
    )
    if (normalized.isBlank()) return emptyList()

    val lower = normalized.lowercase()
    val hasStockIntent = lower.contains("price") || lower.contains("stock") || lower.contains("share")
    if (!hasStockIntent) return emptyList()

    val candidate = normalized
        .replace(Regex("""(?i)\b(ok|okay|so|then)\b"""), " ")
        .replace(Regex("""(?i)\b(what's|whats|what is|tell me|give me|show me|provide|get|please|can you|could you)\b"""), " ")
        .replace(Regex("""(?i)\b(the|latest|current|today|now|currently|lastest)\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '?', '.', '!', ',', ':', ';')

    val intentPatterns = listOf(
        Regex("""(?i)^(?:stock\s+price|share\s+price|stock prices|share prices|price|prices|stock|stocks|share|shares|quote|quotes)\s+(?:of|for)\s+(.+)$"""),
        Regex("""(?i)^(.+?)\s+(?:stock\s+price|share\s+price|stock prices|share prices|price|prices|stock|stocks|share|shares|quote|quotes)$""")
    )

    val subjectRegion = intentPatterns.firstNotNullOfOrNull { pattern ->
        pattern.matchEntire(candidate)?.groupValues?.getOrNull(1)?.trim()
    } ?: return emptyList()

    return splitSubjectCandidates(subjectRegion)
}

private fun extractFollowUpSubject(userText: String): String? {
    val normalized = stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userText)
    )
    if (normalized.isBlank()) return null

    val subject = normalized
        .replace(Regex("""(?i)^(what about|how about|and)\s+"""), "")
        .trim(' ', '?', '.', '!', ',', ':', ';')

    return subject.takeIf { it.isNotBlank() }?.take(120)
}

private fun extractFollowUpSubjects(userText: String): List<String> {
    val normalized = stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userText)
    )
    if (normalized.isBlank()) return emptyList()

    val subjectText = normalized
        .replace(Regex("""(?i)^(what about|how about|and)\s+"""), "")
        .trim(' ', '?', '.', '!', ',', ':', ';')

    return splitSubjectCandidates(subjectText)
}

internal fun isLikelyWebGroundingFollowUp(
    userText: String,
    previousUserText: String?
): Boolean {
    val previous = previousUserText
        ?.let { stripInjectedWebGroundingContext(stripQuotedReplyContext(it)) }
        ?.takeIf { it.isNotBlank() }
        ?: return false

    if (!shouldUseWebGrounding(previous)) {
        return false
    }

    val current = stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userText)
    ).lowercase()
    if (current.isBlank() || shouldUseWebGrounding(current)) {
        return false
    }

    if (current.startsWith("what about ") || current.startsWith("how about ")) {
        return true
    }
    if (current.startsWith("and ") && current.length <= 80) {
        return true
    }

    val wordCount = current.split(Regex("""\s+""")).count { it.isNotBlank() }
    return current.endsWith("?") && wordCount in 1..4
}

internal fun deriveWebSearchQuery(userText: String): String? {
    val normalized = stripInjectedWebGroundingContext(
        stripQuotedReplyContext(userText)
    )
    if (normalized.isBlank()) return null

    val stripped = normalized
        .replace(Regex("""(?i)\b(ok|okay|so|then)\b"""), " ")
        .replace(Regex("""(?i)\b(can you|could you|please)\b"""), " ")
        .replace(Regex("""(?i)\b(search|look up|lookup|google|find|check)\b"""), " ")
        .replace(Regex("""(?i)\b(online|on the internet|on the web|internet|web)\b"""), " ")
        .replace(Regex("""(?i)\bfor me\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '?', '.', '!', ',', ':', ';')
        .replace(Regex("""(?i)^for\s+"""), "")

    return stripped.takeIf { it.isNotBlank() }?.take(180)
}

internal fun deriveWebSearchQueries(messages: List<ChatMessage>): List<String> {
    val lastUserMessage = messages.lastOrNull { it.role == "user" } ?: return emptyList()
    val currentUserText = lastUserMessage.content

    if (shouldUseWebGrounding(currentUserText)) {
        val stockSubjects = extractStockSubjects(currentUserText)
        if (stockSubjects.isNotEmpty()) {
            return stockSubjects.map { "$it stock price yahoo finance" }
        }

        return listOfNotNull(deriveWebSearchQuery(currentUserText))
    }

    val previousUserText = previousComparableUserText(messages)
    if (!isLikelyWebGroundingFollowUp(currentUserText, previousUserText)) {
        return emptyList()
    }

    val previousQuery = previousUserText
        ?.let(::deriveWebSearchQuery)
        ?: return emptyList()
    val descriptor = deriveFollowUpQueryDescriptor(previousQuery)
    val subjects = extractFollowUpSubjects(currentUserText)
        .ifEmpty { listOfNotNull(extractFollowUpSubject(currentUserText)) }

    return subjects
        .map { "$it $descriptor".replace(Regex("""\s+"""), " ").trim() }
        .distinct()
        .take(4)
}

internal fun deriveFollowUpQueryDescriptor(previousQuery: String): String {
    val normalized = previousQuery.lowercase()
    return when {
        normalized.contains("price") || normalized.contains("stock") || normalized.contains("share") ->
            "stock price yahoo finance"
        normalized.contains("news") ->
            "latest news"
        normalized.contains("version") || normalized.contains("update") || normalized.contains("updated") ->
            "latest version"
        normalized.contains("release date") ->
            "latest release date"
        normalized.contains("release") ->
            "latest release"
        normalized.contains("ceo") ->
            "current CEO"
        normalized.contains("president") ->
            "current president"
        normalized.contains("who is currently") || normalized.contains("who's currently") ->
            "current"
        normalized.contains("current") ->
            "current status"
        else ->
            previousQuery.take(90)
    }
}

internal fun deriveWebSearchQuery(messages: List<ChatMessage>): String? {
    return deriveWebSearchQueries(messages).firstOrNull()
}

internal fun shouldUseWebGrounding(messages: List<ChatMessage>): Boolean {
    val lastUserMessage = messages.lastOrNull { it.role == "user" } ?: return false
    if (shouldUseWebGrounding(lastUserMessage.content)) {
        return true
    }

    return isLikelyWebGroundingFollowUp(
        userText = lastUserMessage.content,
        previousUserText = previousComparableUserText(messages)
    )
}

class BraveHtmlSearchProvider(
    private val client: OkHttpClient
) : WebSearchProvider {

    override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
        withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url("https://search.brave.com/search?q=$encodedQuery&source=web")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val html = response.body?.string().orEmpty()
                if (html.isBlank()) return@use emptyList()
                if (html.contains("PoW Captcha", ignoreCase = true)) return@use emptyList()

                val document = Jsoup.parse(html)
                document.select("div.snippet[data-type=web]")
                    .mapNotNull { element ->
                        val link = element.selectFirst("a[href^=http]") ?: return@mapNotNull null
                        val title = element.selectFirst("div.title")?.text()
                            ?.replace(Regex("""\s+"""), " ")
                            ?.trim()
                            .orEmpty()
                        val url = link.attr("href").trim()
                        val snippet = element.selectFirst("div.generic-snippet div.content")
                            ?.text()
                            ?.replace(Regex("""\s+"""), " ")
                            ?.trim()
                            .orEmpty()

                        if (title.isBlank() || url.isBlank()) return@mapNotNull null
                        WebGroundingSource(
                            title = title.take(180),
                            url = url,
                            snippet = snippet.take(420)
                        )
                    }
                    .distinctBy { it.url }
                    .take(maxResults)
            }
        }
}

class DuckDuckGoHtmlSearchProvider(
    private val client: OkHttpClient
) : WebSearchProvider {

    override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val html = response.body?.string().orEmpty()
                if (html.isBlank()) return@use emptyList()

                val document = Jsoup.parse(html)
                document.select("div.result")
                    .mapNotNull { element ->
                        val link = element.selectFirst("a.result__a[href]") ?: return@mapNotNull null
                        val title = link.text()
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                        val url = link.attr("href").trim()
                        val snippet = element.selectFirst(".result__snippet")
                            ?.text()
                            ?.replace(Regex("""\s+"""), " ")
                            ?.trim()
                            .orEmpty()

                        if (title.isBlank() || url.isBlank()) return@mapNotNull null
                        WebGroundingSource(
                            title = title.take(180),
                            url = url,
                            snippet = snippet.take(420)
                        )
                    }
                    .distinctBy { it.url }
                    .take(maxResults)
            }
        }
}

class FallbackWebSearchProvider(
    private val providers: List<WebSearchProvider>
) : WebSearchProvider {

    override suspend fun search(query: String, maxResults: Int): List<WebGroundingSource> {
        for (provider in providers) {
            val results = runCatching {
                provider.search(query, maxResults)
            }.getOrElse { emptyList() }
            if (results.isNotEmpty()) {
                return results
            }
        }
        return emptyList()
    }
}

class WebGroundingService(
    private val searchProvider: WebSearchProvider,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private fun rankSourcesForQuery(
        query: String,
        sources: List<WebGroundingSource>
    ): List<WebGroundingSource> {
        val normalizedQuery = query.lowercase()
        val stockIntent = normalizedQuery.contains("stock price") ||
            normalizedQuery.contains("stock quote") ||
            normalizedQuery.contains("quote") ||
            normalizedQuery.contains("yahoo finance")
        if (!stockIntent) return sources

        fun score(source: WebGroundingSource): Int {
            val haystack = buildString {
                append(source.title)
                append(' ')
                append(source.snippet)
                append(' ')
                append(source.url)
            }.lowercase()

            var score = 0
            if ("yahoo finance" in haystack) score += 10
            if ("marketwatch" in haystack) score += 9
            if ("nasdaq" in haystack) score += 8
            if ("google finance" in haystack) score += 8
            if ("stock price" in haystack) score += 7
            if ("stock quote" in haystack) score += 7
            if ("quote" in haystack) score += 5
            if ("finance" in haystack) score += 4
            if ("news" in haystack) score -= 4
            if ("analysis" in haystack) score -= 2
            if ("earnings" in haystack) score -= 2
            return score
        }

        return sources.sortedByDescending(::score)
    }

    private val searchCache = LinkedHashMap<String, List<WebGroundingSource>>(16, 0.75f, true)
    private val searchCacheMutex = Mutex()

    private suspend fun searchWithCache(
        query: String,
        maxResults: Int
    ): List<WebGroundingSource> {
        val cacheKey = "$maxResults::$query"
        searchCacheMutex.withLock {
            searchCache[cacheKey]?.let { cached ->
                logWebGroundingDebug("cache hit query=\"$query\" results=${cached.size}")
                return cached
            }
        }

        val freshResults = runCatching {
            searchProvider.search(query, maxResults = maxResults)
        }.getOrElse { emptyList() }
        val rankedResults = rankSourcesForQuery(query, freshResults)

        searchCacheMutex.withLock {
            searchCache[cacheKey] = rankedResults
            while (searchCache.size > 24) {
                val eldestKey = searchCache.entries.firstOrNull()?.key ?: break
                searchCache.remove(eldestKey)
            }
        }

        return rankedResults
    }

    private suspend fun fetchBody(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun extractStockSubjectFromQuery(query: String): String {
        return query
            .replace(Regex("""(?i)\bstock price yahoo finance\b"""), " ")
            .replace(Regex("""(?i)\bstock price\b"""), " ")
            .replace(Regex("""(?i)\byahoo finance\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '?', '.', '!', ',', ':', ';')
    }

    private fun parseTickerCandidates(source: WebGroundingSource): List<String> {
        val candidates = linkedSetOf<String>()
        val titleMatches = listOf(
            Regex("""\(([A-Z]{1,5})\)"""),
            Regex("""\b([A-Z]{1,5})\s+(?:stock|share|quote)\b""", RegexOption.IGNORE_CASE)
        )
        titleMatches.forEach { regex ->
            regex.findAll(source.title).forEach { match ->
                match.groupValues.getOrNull(1)?.uppercase()?.let { candidates.add(it) }
            }
        }

        val urlPatterns = listOf(
            Regex("""/quote/([A-Za-z\.\-]{1,12})(?:[/?]|$)"""),
            Regex("""/investing/stock/([A-Za-z\.\-]{1,12})(?:[/?]|$)"""),
            Regex("""[?&]s=([A-Za-z\.\-]{1,12})(?:[&]|$)""")
        )
        urlPatterns.forEach { regex ->
            regex.findAll(source.url).forEach { match ->
                val raw = match.groupValues.getOrNull(1).orEmpty()
                val cleaned = raw
                    .substringBefore('.')
                    .uppercase()
                    .replace(Regex("""[^A-Z]"""), "")
                if (cleaned.length in 1..5) {
                    candidates.add(cleaned)
                }
            }
        }

        return candidates.toList()
    }

    internal fun extractTickerCandidatesFromSearchHtml(html: String): List<String> {
        val weighted = linkedMapOf<String, Int>()
        val patterns = listOf(
            Regex("""["']symbol["']\s*:\s*["']([A-Z]{1,5})["']"""),
            Regex("""\(([A-Z]{1,5})\)"""),
            Regex("""\b([A-Z]{1,5})\.(?:US|DE|UK|PL)\b""")
        )
        patterns.forEachIndexed { index, regex ->
            val weight = 6 - index
            regex.findAll(html).forEach { match ->
                val ticker = match.groupValues.getOrNull(1)?.uppercase().orEmpty()
                if (ticker.length in 1..5 && ticker !in stockTickerStopWords) {
                    weighted[ticker] = (weighted[ticker] ?: 0) + weight
                }
            }
        }

        return weighted.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    private suspend fun resolveTickerFromQueryPage(query: String): String? {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val html = fetchBody("https://search.brave.com/search?q=$encodedQuery&source=web") ?: return null
        return extractTickerCandidatesFromSearchHtml(html).firstOrNull()
    }

    private suspend fun resolveTickerForSubject(
        subject: String,
        query: String,
        sources: List<WebGroundingSource>
    ): String? {
        val normalizedSubject = subject.trim()
        val simpleTicker = normalizedSubject
            .replace(Regex("""[^A-Za-z]"""), "")
            .takeIf { it.length in 1..5 && normalizedSubject.split(Regex("""\s+""")).size == 1 }
            ?.uppercase()
        if (simpleTicker != null) {
            return simpleTicker
        }

        val subjectLower = normalizedSubject.lowercase()
        val weighted = linkedMapOf<String, Int>()
        sources.forEach { source ->
            val haystack = buildString {
                append(source.title)
                append(' ')
                append(source.snippet)
                append(' ')
                append(source.url)
            }.lowercase()
            val subjectScore = when {
                haystack.contains(subjectLower) -> 3
                subjectLower.split(Regex("""\s+""")).all { token -> token.isNotBlank() && haystack.contains(token) } -> 2
                else -> 1
            }
            parseTickerCandidates(source).forEach { ticker ->
                weighted[ticker] = (weighted[ticker] ?: 0) + subjectScore
            }
        }
        return weighted.maxByOrNull { it.value }?.key
            ?: resolveTickerFromQueryPage(query)
    }

    private fun parseStooqQuote(csv: String): Pair<String, String>? {
        val line = csv.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isBlank()) return null
        val columns = line.split(",")
        if (columns.size < 7) return null
        val close = columns.getOrNull(6)?.trim().orEmpty()
        if (close.isBlank() || close == "N/D") return null
        val date = columns.getOrNull(1)?.trim().orEmpty()
        val time = columns.getOrNull(2)?.trim().orEmpty()
        val prettyDate = if (date.length == 8) {
            "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
        } else {
            date
        }
        val prettyTime = if (time.length == 6) {
            "${time.substring(0, 2)}:${time.substring(2, 4)}:${time.substring(4, 6)}"
        } else {
            time
        }
        val suffix = buildString {
            if (prettyDate.isNotBlank()) {
                append("as of ")
                append(prettyDate)
                if (prettyTime.isNotBlank()) {
                    append(" ")
                    append(prettyTime)
                    append(" UTC")
                }
            }
        }.trim()
        return close to suffix
    }

    private suspend fun resolveStockFacts(
        queries: List<String>,
        sources: List<WebGroundingSource>
    ): List<WebGroundingFact> {
        return coroutineScope {
            queries.map { query ->
                async {
                    val subject = extractStockSubjectFromQuery(query)
                    if (subject.isBlank()) return@async null

                    val querySources = sources.filter { it.sourceQuery == query }
                    val ticker = resolveTickerForSubject(subject, query, querySources) ?: return@async null
                    val quoteAttempts = listOf("${ticker.lowercase()}.us", ticker.lowercase())
                    val resolved = quoteAttempts.firstNotNullOfOrNull { symbolKey ->
                        val url = "https://stooq.com/q/l/?s=$symbolKey&i=d"
                        val csv = fetchBody(url) ?: return@firstNotNullOfOrNull null
                        parseStooqQuote(csv)?.let { quote ->
                            Triple(url, quote.first, quote.second)
                        }
                    } ?: return@async null

                    val value = buildString {
                        append("${resolved.second} USD")
                        if (resolved.third.isNotBlank()) {
                            append(" ")
                            append(resolved.third)
                        }
                        append(" (ticker $ticker)")
                    }
                    WebGroundingFact(
                        label = "Latest available stock price for $subject",
                        value = value,
                        sourceTitle = "Stooq quote for $ticker",
                        sourceUrl = resolved.first,
                        sourceQuery = query
                    )
                }
            }.mapNotNull { it.await() }
        }
    }

    private suspend fun resolveCurrentTimeFacts(
        queries: List<String>
    ): List<WebGroundingFact> {
        return coroutineScope {
            queries.map { query ->
                async {
                    val location = query
                        .replace(Regex("""(?i)^current time in\s+"""), "")
                        .trim()
                    if (location.isBlank()) return@async null

                    val encodedLocation = URLEncoder.encode(location, Charsets.UTF_8.name())
                    val url = "https://wttr.in/$encodedLocation?format=j1"
                    val body = fetchBody(url) ?: return@async null
                    val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                        ?: return@async null
                    val currentCondition = root.getAsJsonArray("current_condition")
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?: return@async null
                    val localObservation = currentCondition.get("localObsDateTime")
                        ?.asString
                        ?.trim()
                        .orEmpty()
                    if (localObservation.isBlank()) return@async null

                    val area = root.getAsJsonArray("nearest_area")
                        ?.firstOrNull()
                        ?.asJsonObject
                    val region = area?.getAsJsonArray("region")
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?.get("value")
                        ?.asString
                        ?.trim()
                        .orEmpty()
                    val country = area?.getAsJsonArray("country")
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?.get("value")
                        ?.asString
                        ?.trim()
                        .orEmpty()
                    val locationLabel = listOf(region, country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                        .ifBlank { location }

                    WebGroundingFact(
                        label = "Current local time in $location",
                        value = "$localObservation ($locationLabel)",
                        sourceTitle = "wttr.in current conditions for $location",
                        sourceUrl = url,
                        sourceQuery = query
                    )
                }
            }.mapNotNull { it.await() }
        }
    }

    suspend fun prepareMessagesIfNeeded(
        messages: List<ChatMessage>,
        plannedQueries: List<String>? = null,
        statusText: String = "Looking online for fresh results…",
        onStatusChanged: (String?) -> Unit = {},
        intent: ConversationIntent = ConversationIntent.NONE
    ): PreparedGroundedMessages {
        if (plannedQueries == null && !shouldUseWebGrounding(messages)) {
            return PreparedGroundedMessages(messages = messages, grounding = null)
        }

        val queries = plannedQueries
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: deriveWebSearchQueries(messages)
            .ifEmpty { return PreparedGroundedMessages(messages = messages, grounding = null) }

        onStatusChanged(statusText)
        logWebGroundingDebug("derived queries=${queries.joinToString(" | ")}")
        val timeQueries = queries.filter { it.startsWith("current time in ", ignoreCase = true) }
        val timeFacts = if (intent == ConversationIntent.CURRENT_TIME || timeQueries.isNotEmpty()) {
            resolveCurrentTimeFacts(timeQueries.ifEmpty { queries })
        } else {
            emptyList()
        }

        val maxResultsPerQuery = if (queries.size > 1) 3 else 5
        val sources = if (timeQueries.size == queries.size && timeQueries.isNotEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                queries.map { query ->
                    async {
                        val querySources = searchWithCache(query, maxResultsPerQuery)
                            .map { source -> source.copy(sourceQuery = query) }
                        logWebGroundingDebug("query=\"$query\" results=${querySources.size}")
                        querySources
                    }
                }.flatMap { it.await() }
            }
        }
        onStatusChanged(null)

        val stockFacts = if (intent == ConversationIntent.STOCK_PRICE || queries.any { it.contains("stock price", ignoreCase = true) }) {
            resolveStockFacts(queries, sources)
        } else {
            emptyList()
        }
        if (stockFacts.isNotEmpty()) {
            logWebGroundingDebug("resolved stock facts=${stockFacts.size} queries=${queries.size}")
        }
        val facts = timeFacts + stockFacts

        if (sources.isEmpty() && facts.isEmpty()) {
            logWebGroundingDebug("no sources found for queries=${queries.joinToString(" | ")}")
            return PreparedGroundedMessages(messages = messages, grounding = null)
        }

        val grounding = WebGroundingResult(
            query = queries.joinToString(" | "),
            sources = sources,
            facts = facts
        )
        return PreparedGroundedMessages(
            messages = applyGrounding(messages, grounding),
            grounding = grounding
        )
    }

    fun applyGrounding(
        messages: List<ChatMessage>,
        grounding: WebGroundingResult
    ): List<ChatMessage> {
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex == -1) return messages

        val groundedUserMessage = messages[lastUserIndex].copy(
            content = buildString {
                appendLine(grounding.toPromptBlock())
                if (messages[lastUserIndex].content.isNotBlank()) {
                    appendLine()
                    append(messages[lastUserIndex].content)
                }
            }.trim()
        )

        return messages.mapIndexed { index, message ->
            if (index == lastUserIndex) groundedUserMessage else message
        }
    }
}

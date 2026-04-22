package com.mad.screenagent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Test
import org.junit.Assert.*

class MarkdownLinkParsingTest {
    
    /**
     * Tests that markdown links "[text](url)" are properly annotated.
     * The display text should show, and the URL should be annotated for clicking.
     */
    @Test
    fun testMarkdownLinkAnnotation() {
        val text = "Check this [link](https://example.com) out"
        val annotated = buildMarkdownAnnotatedString(text)
        
        // Verify text content (markdown syntax removed)
        assertEquals("Check this link out", annotated.text)
        
        // Verify URL annotation exists
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("https://example.com", urlAnnotations[0].item)
        
        // Verify annotation points to correct text segment
        val annotation = urlAnnotations[0]
        assertEquals("link", annotated.text.substring(annotation.start, annotation.end))
    }
    
    @Test
    fun testMultipleMarkdownLinks() {
        val text = "[Google](https://google.com) and [GitHub](https://github.com)"
        val annotated = buildMarkdownAnnotatedString(text)
        
        assertEquals("Google and GitHub", annotated.text)
        
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(2, urlAnnotations.size)
        assertEquals("https://google.com", urlAnnotations[0].item)
        assertEquals("https://github.com", urlAnnotations[1].item)
    }
    
    @Test
    fun testBareURLAnnotation() {
        val text = "Visit https://example.com today"
        val annotated = buildMarkdownAnnotatedString(text)
        
        assertEquals("Visit https://example.com today", annotated.text)
        
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("https://example.com", urlAnnotations[0].item)
    }
    
    @Test
    fun testWWWURLConversion() {
        val text = "Visit www.example.com"
        val annotated = buildMarkdownAnnotatedString(text)
        
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(1, urlAnnotations.size)
        // www.example.com should be converted to https://www.example.com
        assertEquals("https://www.example.com", urlAnnotations[0].item)
    }
    
    @Test
    fun testMixedLinkFormats() {
        val text = "Check [docs](https://docs.example.com) or visit www.example.com"
        val annotated = buildMarkdownAnnotatedString(text)
        
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(2, urlAnnotations.size)
        assertEquals("https://docs.example.com", urlAnnotations[0].item)
        assertEquals("https://www.example.com", urlAnnotations[1].item)
    }
    
    @Test
    fun testNoURLs() {
        val text = "Just plain text with no links"
        val annotated = buildMarkdownAnnotatedString(text)
        
        assertEquals(text, annotated.text)
        
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(0, urlAnnotations.size)
    }
    
    @Test
    fun testURLInsideMarkdownIsNotDuplicated() {
        val text = "[https://example.com](https://example.com)"
        val annotated = buildMarkdownAnnotatedString(text)
        
        // Should show the URL as display text
        assertEquals("https://example.com", annotated.text)
        
        // Should have exactly one annotation (not double-counted)
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("https://example.com", urlAnnotations[0].item)
    }
    
    @Test
    fun testOpenRouterURL() {
        val text = "OpenRouter: https://openrouter.ai"
        val annotated = buildMarkdownAnnotatedString(text)
        
        assertEquals("OpenRouter: https://openrouter.ai", annotated.text)
        
        val urlAnnotations = annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.text.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("https://openrouter.ai", urlAnnotations[0].item)
        
        // Verify the annotation spans the correct text
        val annotation = urlAnnotations[0]
        assertEquals("https://openrouter.ai", annotated.text.substring(annotation.start, annotation.end))
    }
    
    // Helper function that mimics the actual markdown parsing logic
    private fun buildMarkdownAnnotatedString(text: String): androidx.compose.ui.text.AnnotatedString {
        return buildAnnotatedString {
            appendMarkdownInlineWithUrls(text, Color.Blue)
        }
    }
    
    // This is the actual logic from MarkdownMessageText.kt
    private fun androidx.compose.ui.text.AnnotatedString.Builder.appendMarkdownInlineWithUrls(
        text: String,
        color: Color
    ) {
        var currentIndex = 0
        var lastProcessedIndex = 0
        
        data class LinkMatch(val startInOriginal: Int, val endInOriginal: Int, val displayText: String, val url: String, val isMarkdown: Boolean)
        val allLinks = mutableListOf<LinkMatch>()
        
        // Find markdown links
        val markdownLinkRegex = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
        markdownLinkRegex.findAll(text).forEach { match ->
            val displayText = match.groupValues[1]
            val url = match.groupValues[2]
            allLinks.add(LinkMatch(match.range.first, match.range.last + 1, displayText, url, true))
        }
        
        // Find bare URLs (but skip if inside markdown links)
        val urlRegex = Regex("""(https?://[^\s<>\]]+|www\.[^\s<>\]]+)""")
        val markdownRanges = allLinks.map { IntRange(it.startInOriginal, it.endInOriginal - 1) }
        urlRegex.findAll(text).forEach { match ->
            val isInsideMarkdownLink = markdownRanges.any { 
                match.range.first >= it.first && match.range.last <= it.last 
            }
            if (!isInsideMarkdownLink) {
                allLinks.add(LinkMatch(match.range.first, match.range.last + 1, match.value, match.value, false))
            }
        }
        
        allLinks.sortBy { it.startInOriginal }
        
        for (link in allLinks) {
            if (link.startInOriginal > lastProcessedIndex) {
                append(text.substring(lastProcessedIndex, link.startInOriginal))
            }
            
            val displayStart = length
            append(link.displayText)
            val displayEnd = length
            
            val finalUrl = if (link.url.startsWith("www.")) "https://" + link.url else link.url
            
            addStringAnnotation(
                tag = "URL",
                annotation = finalUrl,
                start = displayStart,
                end = displayEnd
            )
            
            lastProcessedIndex = link.endInOriginal
        }
        
        if (lastProcessedIndex < text.length) {
            append(text.substring(lastProcessedIndex))
        }
    }
}

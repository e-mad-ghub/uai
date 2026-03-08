package com.example.uai.ai

import okhttp3.OkHttpClient

/**
 * OpenRouter uses the OpenAI-compatible API. We reuse OpenAiProvider
 * with a different base URL and an extra HTTP-Referer header.
 * The extra header is added via an OkHttp interceptor at call time
 * by passing a custom client.
 */
class OpenRouterProvider(client: OkHttpClient) : AiProvider by OpenAiProvider(
    client = client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("HTTP-Referer", "https://uai.app")
                .header("X-Title", "UAI")
                .build()
            chain.proceed(request)
        }
        .build(),
    baseUrl = "https://openrouter.ai/api/v1"
)

package com.mad.screenagent.data.model

data class ProviderModelCatalogEntry(
    val id: String,
    val displayName: String = id,
    val createdAt: Long = 0L
)

data class ProviderModelCatalog(
    val fetchedAt: Long = 0L,
    val models: List<ProviderModelCatalogEntry> = emptyList()
)

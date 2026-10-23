package com.example.sakhi.core.model

data class DistressAssessment(
    val category: DistressCategory,
    val confidence: Float?,
    val transcript: String?,
    val contextSummary: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val providerStatus: String = "ACTIVE",
    val triggerPhraseMatch: Boolean = false
)

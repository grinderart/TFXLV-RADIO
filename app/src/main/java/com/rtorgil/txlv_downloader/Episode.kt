package com.rtorgil.txlv_downloader

data class Episode(
    val id: String,
    val title: String,
    val date: String,
    val rawDate: String,
    val audioUrl: String,
    val duration: String? = null,
    val description: String? = null,
    val invitados: String? = null,
    val tema: String? = null,
    val tags: List<String> = emptyList()
)

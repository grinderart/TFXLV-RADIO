package com.rtorgil.txlv_downloader

data class Episode(
    val id: String,
    val title: String,
    val date: String,
    val audioUrl: String,
    val duration: String? = null
)
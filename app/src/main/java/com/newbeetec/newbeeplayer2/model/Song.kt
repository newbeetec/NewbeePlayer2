package com.newbeetec.newbeeplayer2.model

data class Song(
    val uri: String,       // content:// URI 字符串
    val title: String,
    val artist: String = "未知艺术家"
)
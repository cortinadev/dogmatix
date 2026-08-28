package com.cortinadev.dogmatix.data.model

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity

data class DownloadableFileWithTags(
    val file: DownloadableFileEntity,
    val tags: List<String>
)

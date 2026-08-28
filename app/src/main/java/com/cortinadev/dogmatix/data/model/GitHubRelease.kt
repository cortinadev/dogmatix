package com.cortinadev.dogmatix.data.model

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name")
    val tagName: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("published_at")
    val publishedAt: String,
    @SerializedName("prerelease")
    val prerelease: Boolean,
    @SerializedName("draft")
    val draft: Boolean
)

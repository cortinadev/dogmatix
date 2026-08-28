package com.cortinadev.dogmatix.data.model

import com.cortinadev.dogmatix.util.Constants

data class TagCategory(
    val name: String,
    val tags: List<String>
)

data class CategorizedTags(
    val regions: TagCategory,
    val languages: TagCategory,
    val videoStandards: TagCategory,
    val contentTypes: TagCategory,
    val fileTypes: TagCategory
)

/** Which filter group a tag belongs to. Tags of the same kind are OR-ed, different kinds are AND-ed. */
enum class TagKind { REGION, LANGUAGE, VIDEO_STANDARD, CONTENT_TYPE, FILE_TYPE }

object TagCategorizer {

    /** Order matters: some tags (e.g. "PAL") would also match the looser language/region checks. */
    fun kindOf(tag: String): TagKind? = when {
        isVideoStandard(tag) -> TagKind.VIDEO_STANDARD
        isContentType(tag) -> TagKind.CONTENT_TYPE
        isRegion(tag) -> TagKind.REGION
        isLanguage(tag) -> TagKind.LANGUAGE
        isFileType(tag) -> TagKind.FILE_TYPE
        else -> null
    }

    /** Groups [tags] by kind; tags of unknown kind are dropped. */
    fun groupByKind(tags: Collection<String>): Map<TagKind, Set<String>> =
        tags.mapNotNull { tag -> kindOf(tag)?.let { it to tag } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    fun categorizeTags(tags: List<String>): CategorizedTags {
        val byKind = groupByKind(tags)
        fun category(name: String, kind: TagKind) =
            TagCategory(name, byKind[kind].orEmpty().sorted())
        return CategorizedTags(
            regions = category("Regions", TagKind.REGION),
            languages = category("Languages", TagKind.LANGUAGE),
            videoStandards = category("Video Standards", TagKind.VIDEO_STANDARD),
            contentTypes = category("Content Types", TagKind.CONTENT_TYPE),
            fileTypes = category("File Types", TagKind.FILE_TYPE)
        )
    }
    
    private fun isRegion(tag: String): Boolean {
        return tag.uppercase() in Constants.Tags.ALL_REGIONS
    }

    private fun isLanguage(tag: String): Boolean {
        return tag.uppercase().matches(Regex("^[A-Z]{2,3}$"))
    }

    private fun isVideoStandard(tag: String): Boolean {
        return tag.uppercase() in Constants.Tags.VIDEO_STANDARDS
    }

    private fun isContentType(tag: String): Boolean {
        return tag.uppercase() in Constants.Tags.CONTENT_TYPES
    }

    private fun isFileType(tag: String): Boolean {
        return tag.uppercase().matches(Constants.Tags.FILE_EXTENSION_PATTERN)
    }
}

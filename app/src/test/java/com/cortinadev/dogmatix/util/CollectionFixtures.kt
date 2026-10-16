package com.cortinadev.dogmatix.util

/** Fake disk listings for the collection tests: URIs are plain `root/top/path` strings. */
internal object CollectionFixtures {

    const val GBA = "nintendo_gameboy_advance"
    const val SNES = "nintendo_super_nintendo_entertainment_system"
    const val PSX = "sony_playstation"
    const val PS2 = "sony_playstation_2"

    fun file(
        path: String,
        size: Long = 1_000,
        console: String? = GBA,
        top: String = "gba",
        root: String = "root",
        modified: Long = 0,
        hash: String? = null,
        refs: List<String>? = null,
        catalogId: Long? = null
    ): DiskFile {
        val name = path.substringAfterLast('/')
        val folder = path.substringBeforeLast('/', "")
        val parent = listOf(root, top, folder).filter { it.isNotEmpty() }.joinToString("/")
        return DiskFile(
            name = name, path = path, size = size, lastModified = modified,
            uri = "$parent/$name", parentUri = parent, consoleId = console, topFolder = top, rootUri = root,
            depth = path.count { it == '/' }, groupKey = VersionGrouping.groupKey(name),
            catalogId = catalogId, hash = hash, references = refs
        )
    }

    fun folder(path: String, console: String? = GBA, top: String = "gba", root: String = "root", empty: Boolean = false): DiskFolder {
        val name = path.substringAfterLast('/').ifEmpty { top }
        val parentPath = path.substringBeforeLast('/', "")
        val parent = listOf(root, top, parentPath).filter { it.isNotEmpty() }.joinToString("/")
        val uri = if (path.isEmpty()) "$root/$top" else "$parent/$name"
        return DiskFolder(
            name = name, path = path, uri = uri, parentUri = if (path.isEmpty()) root else parent,
            consoleId = console, topFolder = top, rootUri = root, depth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1, empty = empty
        )
    }

    fun snapshot(
        files: List<DiskFile>,
        folders: List<DiskFolder> = emptyList(),
        volumes: List<VolumeUsage> = emptyList(),
        roots: List<LibraryRoot> = emptyList(),
        catalog: List<CatalogRow> = emptyList(),
        catalogGames: Map<String, Int> = emptyMap(),
        catalogExtensions: Map<String, Set<String>> = emptyMap()
    ) = LibrarySnapshot(files, folders, volumes, roots, catalog, catalogGames, catalogExtensions, scannedAt = 0)

    fun row(id: Long, fileName: String, console: String = GBA, size: Long = 1_000) =
        CatalogRow(id, console, VersionGrouping.groupKey(fileName), fileName, size)
}

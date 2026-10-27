package com.cortinadev.dogmatix.storage

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.service.SafDocs
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.util.StorageHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * StorageHelper and SafDocs (the document operations of the trash and the library mover) on a
 * real SAF tree: [TestDocumentsProvider], granted to the app like a folder picked by the user, so
 * no picker and no user folder is involved. Each test starts from an empty tree.
 */
@RunWith(AndroidJUnit4::class)
class StorageProviderTest {

    private val context = TestData.context
    private lateinit var tree: String
    private val root: Uri get() = TestDocuments.rootDocument

    @Before
    fun setUp() {
        tree = TestDocuments.grant().toString()
    }

    @After
    fun tearDown() = TestDocuments.reset()

    private fun write(uri: Uri, text: String) =
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }

    private fun names(dir: Uri): List<String> = StorageHelper.listChildren(context, dir).map { it.name }.sorted()

    // ---- StorageHelper --------------------------------------------------------------------------

    @Test
    fun aGrantedTreeIsAWritableFolder() {
        val folder = StorageHelper.getDocumentFile(context, tree)!!
        assertTrue(folder.exists())
        assertTrue(folder.isDirectory)
        assertTrue(folder.canWrite())
        assertEquals(root, folder.uri)
        assertTrue(StorageHelper.isValidUri(context, tree))
        assertFalse(StorageHelper.isValidUri(context, ""))
        assertNull(StorageHelper.getDocumentFile(context, "file:///sdcard/ROMs"))
        // A tree nobody granted: not usable, and no exception reaches the caller.
        assertFalse(StorageHelper.isValidUri(context, "content://com.android.externalstorage.documents/tree/primary%3ANeverGranted"))
    }

    @Test
    fun nestedFoldersAreCreatedOnceAndReused() {
        val deep = StorageHelper.createDirectory(context, tree, "snes/Disc sets/Final Fantasy")!!
        assertEquals("Final Fantasy", deep.name)
        val again = StorageHelper.createDirectory(context, tree, "/snes//Disc sets/Final Fantasy/")!!
        assertEquals(deep.uri, again.uri)
        assertEquals(listOf("snes"), names(root))
        assertEquals(listOf("Disc sets"), names(TestDocuments.document("snes")))
        // No sub-path: the folder itself.
        assertEquals(root, StorageHelper.createDirectory(context, tree, "")!!.uri)
        // From a console folder's own uri (how per-console folders are stored).
        val snes = TestDocuments.document("snes").toString()
        assertEquals("Disc sets", StorageHelper.createDirectory(context, snes, "Disc sets")!!.name)
        assertEquals(listOf("Disc sets"), names(TestDocuments.document("snes")))
        // Resolved under snes, not at the root (needs the provider to be seen as a documents provider).
        assertEquals(listOf("snes"), names(root))
    }

    @Test
    fun createFileReplacesOrKeepsTheExistingOne() {
        val first = StorageHelper.createFile(context, tree, "snes", "Chrono Trigger (USA).zip")!!
        write(first.uri, "one")
        val kept = StorageHelper.createFile(context, tree, "snes", "Chrono Trigger (USA).zip", overwrite = false)!!
        assertEquals(first.uri, kept.uri)
        assertEquals("one", read(kept.uri))
        val replaced = StorageHelper.createFile(context, tree, "snes", "Chrono Trigger (USA).zip", overwrite = true)!!
        assertEquals("", read(replaced.uri))
        assertEquals(listOf("Chrono Trigger (USA).zip"), names(TestDocuments.document("snes")))
        // With a text type the provider adds its extension, as the system one does (why the app asks for octet-stream).
        assertEquals("★ Search.dgmtx.txt", StorageHelper.createFile(context, tree, "snes", "★ Search.dgmtx", mimeType = "text/plain")!!.name)
        assertEquals("★ Search.dgmtx", StorageHelper.createFile(context, tree, "snes", "★ Search.dgmtx")!!.name)
    }

    @Test
    fun writeTextSafelyReplacesInPlaceWithoutLeftovers() {
        val folder = StorageHelper.getDocumentFile(context, tree)!!
        StorageHelper.writeTextSafely(context, folder, "ES-DE/gamelists/snes", "gamelist.xml", "<gameList>1</gameList>")
        StorageHelper.writeTextSafely(context, folder, "ES-DE/gamelists/snes", "gamelist.xml", "<gameList>2 ★</gameList>")
        val file = StorageHelper.findFile(folder, "ES-DE/gamelists/snes/gamelist.xml")!!
        assertEquals("<gameList>2 ★</gameList>", StorageHelper.readText(context, file))
        // No temporary left and no "gamelist (1).xml" next to it.
        assertEquals(listOf("gamelist.xml"), names(TestDocuments.document("ES-DE/gamelists/snes")))
        // In the folder itself.
        StorageHelper.writeTextSafely(context, folder, "", "custom.xml", "")
        assertEquals("", StorageHelper.readText(context, StorageHelper.findFile(folder, "custom.xml")!!))
    }

    @Test
    fun findFileFollowsAPathAndStopsAtAMissingStep() {
        StorageHelper.createFile(context, tree, "psx/Final Fantasy VII.m3u", "Final Fantasy VII (Disc 1).chd")
        val folder = StorageHelper.getDocumentFile(context, tree)!!
        assertEquals(
            "Final Fantasy VII (Disc 1).chd",
            StorageHelper.findFile(folder, "psx/Final Fantasy VII.m3u/Final Fantasy VII (Disc 1).chd")?.name
        )
        assertNull(StorageHelper.findFile(folder, "psx/Missing/Final Fantasy VII (Disc 1).chd"))
        assertNull(StorageHelper.findFile(folder, "psx/Final Fantasy VII.m3u/Final Fantasy VII (Disc 3).chd"))
        assertEquals(folder.uri, StorageHelper.findFile(folder, "")!!.uri)
    }

    @Test
    fun listChildrenTellsFoldersFromFiles() {
        StorageHelper.createDirectory(context, tree, "snes")
        StorageHelper.createFile(context, tree, "", "readme.txt")
        StorageHelper.createFile(context, tree, "", ".nomedia")
        assertEquals(
            mapOf("snes" to true, "readme.txt" to false, ".nomedia" to false),
            StorageHelper.listChildren(context, root).associate { it.name to it.isDirectory }
        )
        // A child's uri is a document of the same tree, listable in turn.
        val snes = StorageHelper.listChildren(context, root).single { it.isDirectory }
        StorageHelper.createFile(context, tree, "snes", "a.sfc")
        assertEquals(listOf("a.sfc"), StorageHelper.listChildren(context, snes.uri).map { it.name })
    }

    @Test
    fun aMissingFolderIsReportedNotCreated() {
        val gone = TestDocuments.document("gone")
        assertNull(StorageHelper.createDirectory(context, gone.toString(), "snes"))
        assertNull(StorageHelper.createFile(context, gone.toString(), "", "a.zip"))
        assertFalse(StorageHelper.isValidUri(context, gone.toString()))
        assertTrue(StorageHelper.listChildren(context, gone).isEmpty())
        assertNull(SafDocs.list(context, gone))
        assertNull(SafDocs.stat(context, gone))
        assertNull(SafDocs.createFile(context, gone, "a.zip"))
        // Nothing was made on the way.
        assertTrue(names(root).isEmpty())
        // A folder deleted while the app still holds its uri.
        val snes = StorageHelper.createDirectory(context, tree, "snes")!!
        assertTrue(SafDocs.delete(context, snes.uri))
        assertNull(StorageHelper.createFile(context, snes.uri.toString(), "", "a.zip"))
        assertTrue(names(root).isEmpty())
    }

    @Test
    fun freeSpaceIsKnownOnlyForStorageVolumes() {
        // Not a volume of the device: unknown, not zero.
        assertNull(StorageHelper.getFreeBytes(context, tree))
        assertNull(StorageHelper.getFreeBytes(context, "content://com.android.externalstorage.documents/document/primary%3ADownload"))
        assertNull(StorageHelper.getFreeBytes(context, "not a uri"))
        // Shared storage answers from its path, no permission needed.
        val shared = StorageHelper.getFreeBytes(context, "content://com.android.externalstorage.documents/tree/primary%3ADownload")
        assertNotNull(shared)
        assertTrue(shared!! > 0)
    }

    // ---- SafDocs (trash, library mover) ----------------------------------------------------------

    @Test
    fun documentUrisOfATree() {
        assertEquals(root, SafDocs.docUri(tree))
        assertEquals(TestDocuments.document("snes"), SafDocs.docUri(TestDocuments.document("snes").toString()))
        assertNull(SafDocs.docUri("content://com.cortinadev.dogmatix.test.documents/document/root"))
        val game = TestDocuments.document("snes/Game.sfc")
        assertEquals(TestDocuments.treeUri, SafDocs.treeUriOf(game))
        assertEquals(root, SafDocs.treeRootOf(game))
        assertEquals("root/snes/Game.sfc", SafDocs.docId(game))
        assertEquals(TestDocuments.document("psx"), SafDocs.sibling(game, "root/psx"))
    }

    @Test
    fun nameCollisionsGetANumberedNameAndNothingIsOverwritten() {
        val a = SafDocs.createFile(context, root, "Game (USA).zip")!!
        write(a, "A")
        val b = SafDocs.createFile(context, root, "Game (USA).zip")!!
        assertNotEquals(a, b)
        assertEquals(listOf("Game (USA) (1).zip", "Game (USA).zip"), names(root))
        SafDocs.createDir(context, root, "snes")
        SafDocs.createDir(context, root, "snes")
        assertTrue("snes (1)" in names(root))
        // Renaming onto a taken name keeps both.
        val renamed = SafDocs.rename(context, b, "Game (USA).zip")!!
        assertEquals("Game (USA) (2).zip", SafDocs.stat(context, renamed)!!.name)
        assertEquals("A", read(a))
        // A file where a folder is wanted: ensureDirs gives up instead of nesting under it.
        SafDocs.createFile(context, root, "psx")
        assertNull(SafDocs.ensureDirs(context, root, "psx/Disc 1"))
        assertEquals("Disc 1", SafDocs.stat(context, SafDocs.ensureDirs(context, root, "gba/Disc 1")!!)!!.name)
    }

    @Test
    fun zeroByteFiles() = runBlocking {
        val empty = SafDocs.createFile(context, root, "empty.bin")!!
        assertEquals(0L, SafDocs.stat(context, empty)!!.size)
        val copy = SafDocs.createFile(context, root, "copy.bin")!!
        assertEquals(0L, SafDocs.copyInto(context, empty, copy))
        assertEquals("", read(copy))
        assertEquals(0L, SafDocs.folderSize(context, root))
    }

    @Test
    fun theTrashRoundTripMovesAndRenamesWithinTheTree() {
        val snes = SafDocs.ensureDirs(context, root, "snes")!!
        val game = SafDocs.createFile(context, snes, "Chrono Trigger (USA).sfc")!!
        write(game, "ROM")
        // Into the trash: moved to .dogmatix-trash/<batch>/<path> and marked.
        val bin = SafDocs.ensureDirs(context, root, ".dogmatix-trash/batch-1/snes")!!
        val moved = SafDocs.move(context, game, snes, bin)!!
        val trashed = SafDocs.rename(context, moved, "Chrono Trigger (USA).sfc.dogmatix-trashed")!!
        assertTrue(SafDocs.list(context, snes)!!.isEmpty())
        assertEquals("ROM", read(trashed))
        // Restored: its own name, back in its folder.
        val back = SafDocs.rename(context, trashed, "Chrono Trigger (USA).sfc")!!
        val restored = SafDocs.move(context, back, bin, snes)!!
        assertEquals("ROM", read(restored))
        assertEquals(listOf("Chrono Trigger (USA).sfc"), SafDocs.list(context, snes)!!.map { it.name })
        // Its name was taken in the meantime: the provider refuses the move and nothing is overwritten.
        val again = SafDocs.move(context, restored, snes, bin)!!
        write(SafDocs.createFile(context, snes, "Chrono Trigger (USA).sfc")!!, "NEW")
        assertNull(SafDocs.move(context, again, bin, snes))
        assertEquals("ROM", read(again))
        assertEquals("NEW", read(SafDocs.child(context, snes, "Chrono Trigger (USA).sfc")!!.uri))
        // Emptying the trash deletes the folder and everything in it.
        assertTrue(SafDocs.delete(context, SafDocs.child(context, root, ".dogmatix-trash")!!.uri))
        assertNull(SafDocs.child(context, root, ".dogmatix-trash"))
    }

    @Test
    fun theLibraryMoverCopiesToAPartThenRenames() = runBlocking {
        val from = SafDocs.ensureDirs(context, root, "old/snes")!!
        val source = SafDocs.createFile(context, from, "Big Game.sfc")!!
        write(source, "x".repeat(300_000))
        val to = SafDocs.ensureDirs(context, root, "new/snes")!!
        val part = SafDocs.createFile(context, to, "Big Game.sfc.dogmatix-part")!!
        var reported = 0L
        val written = SafDocs.copyInto(context, source, part) { reported += it }
        assertEquals(300_000L, written)
        assertEquals(written, reported)
        assertEquals(300_000L, SafDocs.stat(context, part)!!.size)
        SafDocs.rename(context, part, "Big Game.sfc")!!
        assertTrue(SafDocs.delete(context, source))
        assertEquals(listOf("Big Game.sfc"), SafDocs.list(context, to)!!.map { it.name })
        assertTrue(SafDocs.list(context, from)!!.isEmpty())
        val newDir = SafDocs.child(context, root, "new")!!.uri
        assertEquals(300_000L, SafDocs.folderSize(context, newDir))
        // A whole folder moves in one provider call when the target has no folder of that name.
        val target = SafDocs.ensureDirs(context, root, "moved")!!
        assertNotNull(SafDocs.move(context, SafDocs.child(context, newDir, "snes")!!.uri, newDir, target))
        assertEquals(300_000L, SafDocs.folderSize(context, target))
        assertTrue(SafDocs.list(context, newDir)!!.isEmpty())
        // ...and refuses when it has.
        val old = SafDocs.child(context, root, "old")!!.uri
        assertNull(SafDocs.move(context, SafDocs.child(context, target, "snes")!!.uri, target, old))
    }
}

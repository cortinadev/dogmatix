package com.cortinadev.dogmatix.storage

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.testing.TestData

/**
 * The app side of [TestDocumentsProvider]: [grant] gives the app under test a tree permission on
 * an empty test folder, as if the user had picked it through SAF; [reset] empties it and takes
 * the permission back. The provider lives in the test package's process, so the requests go
 * through `am broadcast` (run by the shell, which can reach the test package).
 */
object TestDocuments {

    val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)

    /** The root folder as a document of the tree (what `DocumentFile.fromTreeUri(...).uri` is). */
    val rootDocument: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, TestDocumentsProvider.ROOT_ID)

    /** A document of the tree by its path under the root ("snes/Game.zip"); it may not exist. */
    fun document(path: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, if (path.isEmpty()) TestDocumentsProvider.ROOT_ID else "${TestDocumentsProvider.ROOT_ID}/$path")

    fun grant(): Uri {
        val out = control("grant")
        check("granted" in out) { "The test documents provider did not grant the tree: $out" }
        TestData.eventually("tree permission granted") { hasPermission() }
        return treeUri
    }

    fun reset() {
        control("reset")
    }

    private fun hasPermission(): Boolean =
        TestData.context.checkUriPermission(treeUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun control(op: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val target = instrumentation.targetContext.packageName
        // -f 32: FLAG_INCLUDE_STOPPED_PACKAGES (the test package is never launched otherwise).
        val command = "am broadcast -f 32 -n $testPackage/${TestDocumentsControl::class.java.name} --es op $op --es target $target"
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }
}

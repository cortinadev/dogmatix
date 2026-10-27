package com.cortinadev.dogmatix.storage;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;

/**
 * Runs in the test package's process, the owner of {@link TestDocumentsProvider}: "grant" empties
 * its folder and grants the app under test a tree permission on it (what the SAF picker does when
 * the user picks a folder), "reset" empties it and takes the permission back. Driven by the tests
 * through {@code am broadcast}; the result data says what was done.
 */
public final class TestDocumentsControl extends BroadcastReceiver {

    private static final int FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    @Override
    public void onReceive(Context context, Intent intent) {
        String op = intent.getStringExtra("op");
        String target = intent.getStringExtra("target");
        Uri tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID);
        File base = TestDocumentsProvider.baseDir(context);
        try {
            if ("grant".equals(op) && target != null) {
                TestDocumentsProvider.deleteRecursively(base);
                //noinspection ResultOfMethodCallIgnored
                base.mkdirs();
                context.grantUriPermission(target, tree, FLAGS);
                setResult(Activity.RESULT_OK, "granted", null);
            } else if ("reset".equals(op)) {
                TestDocumentsProvider.deleteRecursively(base);
                if (target != null) {
                    context.revokeUriPermission(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
                setResult(Activity.RESULT_OK, "reset", null);
            } else {
                setResult(Activity.RESULT_CANCELED, "unknown op " + op, null);
            }
        } catch (RuntimeException e) {
            setResult(Activity.RESULT_CANCELED, "failed: " + e, null);
        }
    }
}

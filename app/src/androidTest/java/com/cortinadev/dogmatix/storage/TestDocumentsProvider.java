package com.cortinadev.dogmatix.storage;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * A documents provider over a folder of the test package's cache, so the storage code can be
 * exercised through SAF without the system picker. It behaves like the external storage provider
 * where the app depends on it: a name already taken gets " (1)" (create and rename), a text MIME
 * type adds its extension (octet-stream keeps the name as given), moving onto an existing name
 * fails, deleting a folder deletes what is inside. Document ids are "root" and "root/a/b".
 *
 * Java on purpose: it runs in the test package's own process, which has no Kotlin runtime.
 */
public final class TestDocumentsProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.cortinadev.dogmatix.test.documents";
    public static final String ROOT_ID = "root";

    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    static File baseDir(Context context) {
        return new File(context.getCacheDir(), "test-documents");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File base() {
        File base = baseDir(getContext());
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        return base;
    }

    private File fileFor(String documentId) throws FileNotFoundException {
        File file;
        if (ROOT_ID.equals(documentId)) {
            file = base();
        } else if (documentId != null && documentId.startsWith(ROOT_ID + "/")) {
            file = new File(base(), documentId.substring(ROOT_ID.length() + 1));
            try {
                if (!file.getCanonicalPath().startsWith(base().getCanonicalPath() + File.separator)) {
                    throw new FileNotFoundException("Outside the root: " + documentId);
                }
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        } else {
            throw new FileNotFoundException("Unknown document " + documentId);
        }
        if (!file.exists()) throw new FileNotFoundException("No document " + documentId);
        return file;
    }

    private String idFor(File file) {
        String basePath = base().getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(basePath)) return ROOT_ID;
        return ROOT_ID + "/" + path.substring(basePath.length() + 1);
    }

    private static String mimeOf(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private void addRow(MatrixCursor cursor, File file) {
        boolean isRoot = file.getAbsolutePath().equals(base().getAbsolutePath());
        int flags = 0;
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (!isRoot) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_MOVE;
        }
        cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, idFor(file))
                .add(Document.COLUMN_DISPLAY_NAME, isRoot ? "Dogmatix tests" : file.getName())
                .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
                .add(Document.COLUMN_FLAGS, flags)
                .add(Document.COLUMN_SIZE, file.isDirectory() ? null : file.length())
                .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_TITLE, "Dogmatix tests")
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_AVAILABLE_BYTES, base().getFreeSpace());
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addRow(cursor, fileFor(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a folder: " + parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] children = parent.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) addRow(cursor, child);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = fileFor(documentId);
        if (file.isDirectory()) throw new FileNotFoundException("A folder: " + documentId);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId != null && parentDocumentId != null && documentId.startsWith(parentDocumentId + "/");
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a folder: " + parentDocumentId);
        boolean directory = Document.MIME_TYPE_DIR.equals(mimeType);
        File file = uniqueFile(parent, directory ? displayName : withExtension(mimeType, displayName), directory);
        try {
            boolean created = directory ? file.mkdir() : file.createNewFile();
            if (!created) throw new IllegalStateException("Could not create " + file);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create " + file, e);
        }
        return idFor(file);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) throw new IllegalArgumentException("The root stays");
        File file = fileFor(documentId);
        if (!deleteRecursively(file)) throw new IllegalStateException("Could not delete " + documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) throw new IllegalArgumentException("The root stays");
        File before = fileFor(documentId);
        if (before.getName().equals(displayName)) return null;
        File after = uniqueFile(before.getParentFile(), displayName, before.isDirectory());
        if (!before.renameTo(after)) throw new IllegalStateException("Could not rename to " + after);
        return idFor(after);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File source = fileFor(sourceDocumentId);
        File target = new File(fileFor(targetParentDocumentId), source.getName());
        if (target.exists()) throw new IllegalStateException("Already exists " + target.getName());
        if (!source.renameTo(target)) throw new IllegalStateException("Could not move to " + target);
        return idFor(target);
    }

    /** Text and other typed files gain the type's extension unless their name already has one of that type. */
    private static String withExtension(String mimeType, String displayName) {
        if (mimeType == null || "application/octet-stream".equals(mimeType)) return displayName;
        MimeTypeMap map = MimeTypeMap.getSingleton();
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0) {
            String fromName = map.getMimeTypeFromExtension(displayName.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (mimeType.equals(fromName)) return displayName;
        }
        String extension = map.getExtensionFromMimeType(mimeType);
        return extension == null ? displayName : displayName + "." + extension;
    }

    /** [name], or "base (n).ext" when it is taken. */
    private static File uniqueFile(File parent, String name, boolean directory) {
        String safe = name.replace('/', '_');
        File file = new File(parent, safe);
        if (!file.exists()) return file;
        int dot = directory ? -1 : safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        for (int n = 1; n <= 32; n++) {
            file = new File(parent, stem + " (" + n + ")" + extension);
            if (!file.exists()) return file;
        }
        throw new IllegalStateException("No free name for " + name);
    }

    static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        return file.delete() || !file.exists();
    }
}

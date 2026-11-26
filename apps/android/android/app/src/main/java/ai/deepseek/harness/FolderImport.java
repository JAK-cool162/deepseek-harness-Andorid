package ai.deepseek.harness;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a folder the user picked with Android's folder picker (phone storage,
 * an SD card, or a USB drive) into the full engine's `~/work/imports/`, where
 * the agent can read it. Only the picked tree is read.
 */
final class FolderImport {
    /** Progress reports on the worker thread. */
    interface Listener {
        void progress(int files, String current);

        void done(File target, int files, String error);
    }

    private FolderImport() {
    }

    /**
     * Copy the tree on a worker thread.
     *
     * @param context any context of this app
     * @param tree tree URI from {@code ACTION_OPEN_DOCUMENT_TREE}
     * @param listener receives progress and the result
     */
    static void start(Context context, Uri tree, Listener listener) {
        ContentResolver resolver = context.getContentResolver();
        File imports = new File(new LinuxEngine(context).workDir(), "imports");
        new Thread(() -> {
            int[] count = { 0 };
            File target = null;
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                String name = safeName(displayName(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)));
                target = unique(new File(imports, name));
                copyTree(resolver, tree, rootId, target, count, listener);
                listener.done(target, count[0], null);
            } catch (IOException | RuntimeException failed) {
                listener.done(target, count[0], failed.getMessage());
            }
        }, "dsh-folder-import").start();
    }

    private static void copyTree(ContentResolver resolver, Uri tree, String documentId, File target, int[] count,
            Listener listener) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("the folder could not be listed");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = safeName(cursor.getString(1));
                File child = new File(target, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    copyTree(resolver, tree, id, child, count, listener);
                    continue;
                }
                try (InputStream in = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                        OutputStream out = new FileOutputStream(child)) {
                    if (in == null) throw new IOException("cannot read " + name);
                    Io.copy(in, out);
                }
                count[0]++;
                listener.progress(count[0], name);
            }
        }
    }

    private static String displayName(ContentResolver resolver, Uri document) {
        try (Cursor cursor = resolver.query(document, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        return "import";
    }

    /** A single path segment: no separators, no dot-only names. */
    static String safeName(String name) {
        String cleaned = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "import";
        return cleaned;
    }

    private static File unique(File wanted) {
        File candidate = wanted;
        for (int index = 2; candidate.exists(); index++) candidate = new File(wanted.getPath() + "-" + index);
        return candidate;
    }
}

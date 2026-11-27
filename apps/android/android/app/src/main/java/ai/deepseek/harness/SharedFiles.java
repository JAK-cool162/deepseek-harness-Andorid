package ai.deepseek.harness;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Read-only provider for files the page shares through Android's share sheet. Each shared file is
 * written to {@code cache/shared/<name>}; the provider is not exported, so other apps read a file
 * only through the one-time read grant on the share intent.
 */
public final class SharedFiles extends ContentProvider {
    /** Authority suffix after the application ID, matching the manifest. */
    static final String AUTHORITY_SUFFIX = ".shared";

    /**
     * Write a file for sharing, replacing an earlier share with the same name.
     *
     * @param context any context of this app
     * @param name file name without directories, already validated by the caller
     * @param bytes file contents
     * @return the content URI to put on the share intent
     * @throws IOException when the file cannot be written
     */
    static Uri put(Context context, String name, byte[] bytes) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cannot create " + directory);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(bytes);
        }
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + AUTHORITY_SUFFIX).appendPath(name).build();
    }

    /**
     * @param name shared file name
     * @return the MIME type a receiving app should see
     */
    static String mimeType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "text/plain";
    }

    private static File directory(Context context) {
        return new File(context.getCacheDir(), "shared");
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.startsWith(".")) throw new FileNotFoundException(String.valueOf(uri));
        File file = new File(directory(getContext()), name);
        if (!file.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("shared files are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = file(uri);
        } catch (FileNotFoundException missing) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? null : mimeType(name);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}

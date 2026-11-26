package ai.deepseek.harness;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Writes files the page offers to the phone's Downloads folder. */
final class Downloads {
    private Downloads() {
    }

    /**
     * Save bytes as a new file in Downloads; Android renames on a name clash.
     *
     * @param context app context
     * @param name file name without directories
     * @param bytes file contents
     * @throws IOException when the file cannot be created or written
     */
    static void save(Context context, String name, byte[] bytes) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Before scoped storage, the app's own Downloads directory needs no permission.
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) throw new IOException("shared storage is unavailable");
            try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
                out.write(bytes);
            }
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType(name));
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IOException("Downloads refused a new file");
        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Downloads gave no output stream");
            out.write(bytes);
        } catch (IOException failed) {
            resolver.delete(item, null, null);
            throw failed;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, values, null, null);
    }

    private static String mimeType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}

package android.media;
import android.content.Context;
public class MediaScannerConnection {
    public interface OnScanCompletedListener {
        void onScanCompleted(String path, android.net.Uri uri);
    }
    public static void scanFile(Context ctx, String[] paths, String[] mimeTypes, OnScanCompletedListener cb) {}
}

package android.content;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Size;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public class ContentResolver {
    public InputStream openInputStream(Uri uri) throws FileNotFoundException {
        if (uri != null && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        throw new FileNotFoundException("Invalid uri: " + uri);
    }

    public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
        return null;
    }

    public Bitmap loadThumbnail(Uri uri, Size size, Object signal) {
        return null;
    }
}

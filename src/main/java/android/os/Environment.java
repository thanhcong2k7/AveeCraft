package android.os;
import java.io.File;
public class Environment {
    public static final String DIRECTORY_DOWNLOADS = "Downloads";
    public static final String DIRECTORY_MUSIC = "Music";
    public static final String DIRECTORY_MOVIES = "Movies";
    public static final String DIRECTORY_PICTURES = "Pictures";
    public static final String MEDIA_MOUNTED = "mounted";
    public static File getExternalStorageDirectory() { return new File(System.getProperty("user.home", ".")); }
    public static String getExternalStorageState() { return MEDIA_MOUNTED; }
    public static File getExternalStoragePublicDirectory(String type) {
        return new File(getExternalStorageDirectory(), type != null ? type : "Pictures");
    }
}

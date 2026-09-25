package android.support.v4.media;

// Used only while compiling; YouTube Music provides the real class at runtime.
public class MediaBrowserCompat {
    public static class MediaItem {
        public static final int FLAG_BROWSABLE = 1;
        public static final int FLAG_PLAYABLE = 2;
        public MediaDescriptionCompat a;

        public MediaItem(MediaDescriptionCompat description, int flags) {
            throw new UnsupportedOperationException("Stub");
        }

        public String a() {
            throw new UnsupportedOperationException("Stub");
        }

        public boolean b() {
            throw new UnsupportedOperationException("Stub");
        }
    }
}

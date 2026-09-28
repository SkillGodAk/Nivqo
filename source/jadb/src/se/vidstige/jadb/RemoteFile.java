package se.vidstige.jadb;

/**
 * Created by vidstige on 2014-03-20
 */
public class RemoteFile {
    private final String path;
    private final String name;

    public RemoteFile(String path) {
        this.path = path;
        this.name = path.substring(path.lastIndexOf("/") + 1);
    }

    public String getName() { return name; }
    public int getSize() { throw new UnsupportedOperationException(); }
    public int getLastModified() { throw new UnsupportedOperationException(); }
    public boolean isDirectory() { throw new UnsupportedOperationException(); }

    public String getPath() { return path;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RemoteFile that = (RemoteFile) o;
        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}

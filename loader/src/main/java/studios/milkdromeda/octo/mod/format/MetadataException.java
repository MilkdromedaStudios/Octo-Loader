package studios.milkdromeda.octo.mod.format;

/** Thrown when a metadata file exists but cannot be understood. */
public class MetadataException extends Exception {
    public MetadataException(String message) {
        super(message);
    }

    public MetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}

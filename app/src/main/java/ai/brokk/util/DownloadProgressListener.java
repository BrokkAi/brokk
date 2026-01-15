package ai.brokk.util;

/**
 * Callback interface for reporting download progress during Maven artifact fetching.
 */
@FunctionalInterface
public interface DownloadProgressListener {
    /**
     * Called during download progress.
     *
     * @param artifactName the name of the artifact being downloaded
     * @param bytesTransferred bytes downloaded so far
     * @param totalBytes total file size, or -1 if unknown
     */
    void onProgress(String artifactName, long bytesTransferred, long totalBytes);
}

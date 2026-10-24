package cz.cuni.mff.releasemanager;

/**
 * Entry point for the Github Release Manager application.
 */
public class Main {
    /**
     * Main method to start the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = ReleaseManager.getInstance().execute(args);
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
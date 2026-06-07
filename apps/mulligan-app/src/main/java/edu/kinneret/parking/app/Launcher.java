package edu.kinneret.parking.app;

/**
 * Entry point launcher for the Mulligan Unified UI Gateway.
 * Bypasses direct JavaFX startup checks.
 */
public class Launcher {
    /**
     * Default constructor for Launcher.
     */
    public Launcher() {
        // Default constructor
    }

    /**
     * Application entry point. Launches the Unified UI Gateway.
     * @param args command line arguments
     */
    public static void main(String[] args) {
        MulliganApp.main(args);
    }
}

package edu.kinneret.parking.recommender;

/**
 * Thin executable entry point for the recommender server. Exists so the JAR can be
 * launched without referencing the JavaFX {@link javafx.application.Application}
 * subclass directly; it simply delegates to {@link RecommenderServerApplication}.
 */
public class Launcher {
    /**
     * Application entry point that forwards all command-line arguments to
     * {@link RecommenderServerApplication#main(String[])}.
     *
     * @param args command-line arguments passed through to the recommender application
     */
    public static void main(String[] args) {
        RecommenderServerApplication.main(args);
    }
}

package edu.kinneret.parking.common.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Controller for handling the initial Welcome screen navigation choices.
 */
public class WelcomeController {

    /**
     * Default constructor for WelcomeController.
     */
    public WelcomeController() {
    }

    /**
     * Navigates to the Login view.
     */
    @FXML
    public void handleLogin() {
        navigate("/views/LoginView.fxml");
    }

    /**
     * Navigates to the Signup view.
     */
    @FXML
    public void handleSignup() {
        navigate("/views/SignupView.fxml");
    }

    /**
     * Handler for settings button (currently placeholder).
     */
    @FXML
    public void handleSettings() {
        // Ignored
    }

    /**
     * Loads the given FXML view and swaps it into the current stage's scene.
     *
     * @param fxml the classpath location of the FXML view to display
     */
    private void navigate(String fxml) {
        try {
            Stage stage = (Stage) Stage.getWindows().filtered(w -> w.isShowing()).get(0);
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            Parent root = loader.load();
            Scene scene = PhoneFrameBuilder.createScaledScene(root, stage);
            scene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());
            stage.setScene(scene);
        } catch (Exception e) {
            edu.kinneret.parking.common.SecurityLogger.logSecurityEvent("Navigation failed: " + e.getClass().getSimpleName());
        }
    }
}

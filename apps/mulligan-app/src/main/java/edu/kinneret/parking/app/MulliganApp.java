package edu.kinneret.parking.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import edu.kinneret.parking.common.ui.PhoneFrameBuilder;
import edu.kinneret.parking.common.ui.LoginController;

/**
 * Main graphical entry point for the Mulligan Parking System Unified Gateway.
 * Orchestrates multi-role login, authentication routing, and graphical transitions
 * across PEO, Customer, and Municipality (MO) application views.
 */
public class MulliganApp extends Application {
    /**
     * Default constructor for MulliganApp.
     */
    public MulliganApp() {
        // Default constructor
    }
    
    static {
        System.setProperty("glass.win.uiScale", "1.0");
        System.setProperty("prism.allowhidpi", "false");
    }
    
    private Stage meoStage;
    private static double scaleFactor = 1.0;
    private static double targetWidth = 470.0;
    private static double targetHeight = 864.0;

    /**
     * Initializes and starts the primary JavaFX stage for the Unified UI Gateway.
     * Sets up global authentication, logout routing, and initial welcome screen.
     * 
     * @param primaryStage the primary application stage window
     */
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Mulligan Gateway");
        primaryStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setResizable(false);
        
        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen.getPrimary().getBounds();
        targetHeight = bounds.getHeight();
        scaleFactor = targetHeight / 864.0;
        targetWidth = 470.0 * scaleFactor;
        
        primaryStage.setMinWidth(targetWidth);
        primaryStage.setMinHeight(targetHeight);
        primaryStage.setMaxWidth(targetWidth);
        primaryStage.setMaxHeight(targetHeight);
        
        primaryStage.setX((bounds.getWidth() - targetWidth) / 2.0); // Center horizontally
        primaryStage.setY(0.0); // Align to the absolute top edge of the screen
        primaryStage.maximizedProperty().addListener((obs, oldVal, newValue) -> {
            if (newValue) {
                primaryStage.setMaximized(false);
            }
        });
        primaryStage.setOnCloseRequest(e -> {
            javafx.application.Platform.exit();
            System.exit(0);
        });

        // Define the global authenticator for the Gateway
        LoginController.authenticator = (user, pass) -> {
            // 1. Check local static maps first
            String storedPass = LoginController.signupPasswords.get(user);
            if (storedPass != null && storedPass.equals(pass)) {
                return true;
            }

            // 2. Query MongoDB cluster dynamically to check network-registered users!
            try {
                edu.kinneret.parking.common.AppConfig config = edu.kinneret.parking.common.AppConfig.fromEnvironment(edu.kinneret.parking.common.AppConfig.ApplicationProfile.MO_UI);
                try (edu.kinneret.parking.common.ParkingRepository repo = new edu.kinneret.parking.common.ParkingRepository(config)) {
                    return repo.authenticateUser(user, pass);
                }
            } catch (Exception ex) {
                System.err.println("Database authentication fallback failed: " + ex.getMessage());
            }
            return false;
        };

        LoginController.onLogout = () -> {
            if (meoStage != null && meoStage.isShowing()) {
                meoStage.close();
            }
            primaryStage.show();
            primaryStage.setAlwaysOnTop(true);
            
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/WelcomeView.fxml"));
                Parent root = loader.load();
                Scene scene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)root, primaryStage);
                scene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());
                primaryStage.setScene(scene);
            } catch (Exception e) {}
        };

        // Handle routing after successful login
        LoginController.onSuccess = () -> {
            String user = LoginController.currentUsername;
            String role = edu.kinneret.parking.common.ui.LoginController.signupRoles.get(user);
            primaryStage.setAlwaysOnTop(true);
            try {
                if ("Customer".equals(role) || "customer".equals(user)) {
                    edu.kinneret.parking.customer.ui.CustomerApp app = new edu.kinneret.parking.customer.ui.CustomerApp();
                    javafx.scene.Parent root = app.createContent(primaryStage);
                    Scene scene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)root, primaryStage);
                    try {
                        scene.getStylesheets().add(getClass().getResource("/customer-style.css").toExternalForm());
                    } catch (Exception ex) {}
                    primaryStage.setScene(scene);
                } else if ("PEO".equals(role) || "peo_service".equals(user)) {
                    edu.kinneret.parking.peo.ui.PEOApp app = new edu.kinneret.parking.peo.ui.PEOApp();
                    javafx.scene.Parent root = app.createContent();
                    Scene scene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)root, primaryStage);
                    try {
                        scene.getStylesheets().add(getClass().getResource("/peo-style.css").toExternalForm());
                    } catch (Exception ex) {}
                    primaryStage.setScene(scene);
                } else if ("MO".equals(role) || "mulligan_admin".equals(user)) {
                    // Hide the phone frame gateway
                    primaryStage.hide();
                    
                    // Open MOApp in a new desktop window
                    meoStage = new Stage();
                    meoStage.setResizable(false);
                    meoStage.setMinWidth(1100);
                    meoStage.setMinHeight(850);
                    meoStage.setMaxWidth(1100);
                    meoStage.setMaxHeight(850);
                    meoStage.maximizedProperty().addListener((obs, oldVal, newValue) -> {
                        if (newValue) {
                            meoStage.setMaximized(false);
                        }
                    });
                    edu.kinneret.parking.mo.ui.MOApp app = new edu.kinneret.parking.mo.ui.MOApp();
                    javafx.scene.Parent root = app.createContent();
                    Scene scene = new Scene(root, 1100, 850);
                    scene.getStylesheets().add(getClass().getResource("/style-mo.css").toExternalForm());
                    meoStage.setScene(scene);
                    meoStage.setTitle("Mulligan Parking System - Municipality Cluster UI");
                    meoStage.show();
                    
                    // Show the phone frame again if MO is closed
                    meoStage.setOnCloseRequest(e -> {
                        primaryStage.show();
                        try {
                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/WelcomeView.fxml"));
                            Parent welcomeRoot = loader.load();
                            Scene welcomeScene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)welcomeRoot, primaryStage);
                            welcomeScene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());
                            primaryStage.setScene(welcomeScene);
                        } catch (Exception ex) {}
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        // Handle routing after successful signup
        edu.kinneret.parking.common.ui.SignupController.onSignup = (role) -> {
            primaryStage.setAlwaysOnTop(true);
            try {
                if ("Customer".equals(role)) {
                    edu.kinneret.parking.customer.ui.CustomerApp app = new edu.kinneret.parking.customer.ui.CustomerApp();
                    javafx.scene.Parent root = app.createContent(primaryStage);
                    Scene scene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)root, primaryStage);
                    try {
                        scene.getStylesheets().add(getClass().getResource("/customer-style.css").toExternalForm());
                    } catch (Exception ex) {}
                    primaryStage.setScene(scene);
                } else if ("PEO".equals(role)) {
                    edu.kinneret.parking.peo.ui.PEOApp app = new edu.kinneret.parking.peo.ui.PEOApp();
                    javafx.scene.Parent root = app.createContent();
                    Scene scene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)root, primaryStage);
                    try {
                        scene.getStylesheets().add(getClass().getResource("/peo-style.css").toExternalForm());
                    } catch (Exception ex) {}
                    primaryStage.setScene(scene);
                } else if ("MO".equals(role)) {
                    primaryStage.hide();
                    meoStage = new Stage();
                    meoStage.setResizable(false);
                    meoStage.setMinWidth(1100);
                    meoStage.setMinHeight(850);
                    meoStage.setMaxWidth(1100);
                    meoStage.setMaxHeight(850);
                    meoStage.maximizedProperty().addListener((obs, oldVal, newValue) -> {
                        if (newValue) {
                            meoStage.setMaximized(false);
                        }
                    });
                    edu.kinneret.parking.mo.ui.MOApp app = new edu.kinneret.parking.mo.ui.MOApp();
                    javafx.scene.Parent root = app.createContent();
                    Scene scene = new Scene(root, 1100, 850);
                    scene.getStylesheets().add(getClass().getResource("/style-mo.css").toExternalForm());
                    meoStage.setScene(scene);
                    meoStage.setTitle("Mulligan Parking System - Municipality Cluster UI");
                    meoStage.show();
                    
                    meoStage.setOnCloseRequest(e -> {
                        primaryStage.show();
                        try {
                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/WelcomeView.fxml"));
                            Parent welcomeRoot = loader.load();
                            Scene welcomeScene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)welcomeRoot, primaryStage);
                            welcomeScene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());
                            primaryStage.setScene(welcomeScene);
                        } catch (Exception ex) {}
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/WelcomeView.fxml"));
            Parent root = loader.load();
            Scene scene = PhoneFrameBuilder.createScaledScene((javafx.scene.Node)root, primaryStage);
            scene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());

            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Application entry point for direct launching.
     * @param args command line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}

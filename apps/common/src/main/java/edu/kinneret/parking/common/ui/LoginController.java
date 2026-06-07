package edu.kinneret.parking.common.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import java.util.function.BiFunction;

/**
 * Controller for handling the User Login interface and authentication.
 */
public class LoginController {

    /**
     * Default constructor for LoginController.
     */
    public LoginController() {
    }

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordTextField;
    @FXML private Button togglePasswordBtn;
    @FXML private Label statusLabel;
    @FXML private Label statusDot;
    @FXML private Label serverUrlLabel;

    private boolean isPasswordVisible = false;

    // Static handlers injected by the main app
    /**
     * Cache map of registered usernames to their passwords.
     */
    public static java.util.Map<String, String> signupPasswords = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Cache map of registered usernames to their vehicle VINs.
     */
    public static java.util.Map<String, String> signupVins = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Cache map of registered usernames to their roles.
     */
    public static java.util.Map<String, String> signupRoles = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String USERS_FILE = "registered_users.properties";

    static {
        signupPasswords.put("customer", "customer_secure_pass_2026");
        signupVins.put("customer", "604-95-839");
        signupRoles.put("customer", "Customer");

        signupPasswords.put("peo_service", "peo_secure_pass_2026");
        signupVins.put("peo_service", "123456789");
        signupRoles.put("peo_service", "PEO");

        signupPasswords.put("mulligan_admin", "admin_ultra_secure_99");
        signupVins.put("mulligan_admin", "999999999");
        signupRoles.put("mulligan_admin", "MO");

        loadUsersFromFile();
        syncUsersFromDatabase();
    }

    /**
     * Loads registered users from the local storage properties file.
     */
    public static void loadUsersFromFile() {
        java.io.File file = new java.io.File(USERS_FILE);
        if (file.exists()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                java.util.Properties props = new java.util.Properties();
                props.load(fis);
                for (String key : props.stringPropertyNames()) {
                    String[] parts = props.getProperty(key).split(";");
                    if (parts.length >= 2) {
                        signupPasswords.put(key, parts[0]);
                        signupRoles.put(key, parts[1]);
                        if (parts.length >= 3 && !parts[2].isEmpty()) {
                            signupVins.put(key, parts[2]);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error loading registered users: " + e.getMessage());
            }
        }
    }

    /**
     * Saves a registered user's credentials and role to the local storage properties file.
     *
     * @param username the user's login email or username
     * @param password the user's password
     * @param role the user's role (e.g. Customer, PEO, MO)
     * @param vin the associated vehicle VIN (optional)
     */
    public static void saveUserToFile(String username, String password, String role, String vin) {
        java.io.File file = new java.io.File(USERS_FILE);
        java.util.Properties props = new java.util.Properties();
        if (file.exists()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                props.load(fis);
            } catch (Exception ignored) {}
        }
        String value = password + ";" + role + ";" + (vin != null ? vin : "");
        props.setProperty(username, value);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            props.store(fos, "Registered Users");
        } catch (Exception e) {
            System.err.println("Error saving registered user: " + e.getMessage());
        }
    }

    /**
     * Spawns a background thread to synchronize users from the MongoDB database collection.
     */
    public static void syncUsersFromDatabase() {
        new Thread(() -> {
            try {
                edu.kinneret.parking.common.AppConfig config = edu.kinneret.parking.common.AppConfig.fromEnvironment(edu.kinneret.parking.common.AppConfig.ApplicationProfile.MO_UI);
                try (edu.kinneret.parking.common.ParkingRepository repo = new edu.kinneret.parking.common.ParkingRepository(config)) {
                    if (repo.getDatabase() != null) {
                        for (org.bson.Document doc : repo.getDatabase().getCollection("users").find()) {
                            String username = doc.getString("username");
                            String password = doc.getString("password");
                            String role = doc.getString("role");
                            String vin = doc.getString("vin");
                            if (username != null && password != null && role != null) {
                                signupPasswords.put(username, password);
                                signupRoles.put(username, role);
                                if (vin != null && !vin.isEmpty()) {
                                    signupVins.put(username, vin);
                                }
                                saveUserToFile(username, password, role, vin);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error syncing users from database: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Authenticator function used to validate login credentials.
     */
    public static BiFunction<String, String, Boolean> authenticator;
    /**
     * Success handler runnable called upon successful authentication.
     */
    public static Runnable onSuccess;
    /**
     * Logout handler runnable called upon logging out of the application.
     */
    public static Runnable onLogout;
    /**
     * Holds the currently logged-in user's username.
     */
    public static String currentUsername;

    /**
     * Initializes the JavaFX controller and configures field style listeners.
     */
    @FXML
    public void initialize() {
        usernameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                usernameField.getStyleClass().remove("field-error");
            }
        });
        passwordField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                passwordField.getStyleClass().remove("field-error");
                passwordTextField.getStyleClass().remove("field-error");
            }
        });
        passwordTextField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                passwordField.getStyleClass().remove("field-error");
                passwordTextField.getStyleClass().remove("field-error");
            }
        });
    }

    /**
     * Handles keyboard events (such as pressing ENTER) to trigger the login action.
     *
     * @param event the keyboard event
     */
    @FXML
    public void handleKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            handleLogin();
        }
    }

    /**
     * Validates inputs, authenticates against configured users, and handles stage transition on success.
     */
    @FXML
    public void handleLogin() {
        String username = usernameField.getText();
        if (username != null) username = username.trim();
        String password = isPasswordVisible ? passwordTextField.getText() : passwordField.getText();
        if (password != null) password = password.trim();

        boolean hasError = false;
        
        usernameField.getStyleClass().remove("field-error");
        passwordField.getStyleClass().remove("field-error");
        passwordTextField.getStyleClass().remove("field-error");

        if (username == null || username.trim().isEmpty()) {
            usernameField.getStyleClass().add("field-error");
            hasError = true;
        }

        if (password == null || password.trim().isEmpty()) {
            passwordField.getStyleClass().add("field-error");
            passwordTextField.getStyleClass().add("field-error");
            hasError = true;
        }

        if (hasError) {
            setStatus("Please fill in all empty fields.", true);
            return;
        }

        boolean userExists = signupPasswords.containsKey(username);
        if (!userExists) {
            try {
                edu.kinneret.parking.common.AppConfig config = edu.kinneret.parking.common.AppConfig.fromEnvironment(edu.kinneret.parking.common.AppConfig.ApplicationProfile.MO_UI);
                try (edu.kinneret.parking.common.ParkingRepository repo = new edu.kinneret.parking.common.ParkingRepository(config)) {
                    if (repo.getDatabase() != null) {
                        org.bson.Document userDoc = repo.getDatabase().getCollection("users")
                                .find(com.mongodb.client.model.Filters.eq("username", username))
                                .first();
                        if (userDoc != null) {
                            userExists = true;
                            // Sync it so we cache it locally!
                            String storedPass = userDoc.getString("password");
                            String role = userDoc.getString("role");
                            String vin = userDoc.getString("vin");
                            signupPasswords.put(username, storedPass);
                            signupRoles.put(username, role);
                            if (vin != null && !vin.isEmpty()) {
                                signupVins.put(username, vin);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!userExists) {
            usernameField.getStyleClass().add("field-error");
            passwordField.getStyleClass().add("field-error");
            passwordTextField.getStyleClass().add("field-error");
            setStatus("Invalid email or password.", true);
            return;
        }

        if (authenticator != null && authenticator.apply(username, password)) {
            currentUsername = username;
            setStatus("Login successful...", false);
            if (onSuccess != null) {
                onSuccess.run();
            }
        } else {
            usernameField.getStyleClass().add("field-error");
            passwordField.getStyleClass().add("field-error");
            passwordTextField.getStyleClass().add("field-error");
            setStatus("Invalid email or password.", true);
        }
    }

    /**
     * Toggles between plaintext and hidden password fields in the user interface.
     */
    @FXML
    public void togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible;
        if (isPasswordVisible) {
            passwordTextField.setText(passwordField.getText());
            passwordTextField.setVisible(true);
            passwordField.setVisible(false);
            togglePasswordBtn.setStyle("-fx-opacity: 0.5;"); // Optional visual feedback
        } else {
            passwordField.setText(passwordTextField.getText());
            passwordField.setVisible(true);
            passwordTextField.setVisible(false);
            togglePasswordBtn.setStyle("-fx-opacity: 1.0;");
        }
    }

    /**
     * Redirects the user interface from the Login view to the Signup view.
     */
    @FXML
    public void handleSignupRedirect() {
        try {
            javafx.stage.Stage stage = (javafx.stage.Stage) statusLabel.getScene().getWindow();
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/views/SignupView.fxml"));
            javafx.scene.Parent signupRoot = loader.load();
            javafx.scene.Scene signupScene = PhoneFrameBuilder.createScaledScene(signupRoot, stage);
            signupScene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());
            stage.setScene(signupScene);
        } catch (Exception e) {
            setStatus("Error navigating to signup.", true);
        }
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setStyle(error ? "-fx-text-fill: #ef4444;" : "-fx-text-fill: #4ade80;");
    }
}

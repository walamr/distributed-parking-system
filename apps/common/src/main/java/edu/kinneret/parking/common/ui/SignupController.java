package edu.kinneret.parking.common.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Controller for handling the User Signup interface and new user registration.
 */
public class SignupController {

    /**
     * Default constructor for SignupController.
     */
    public SignupController() {
    }

    @FXML private TextField fullNameField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordTextField;
    @FXML private Button togglePasswordBtn;
    @FXML private ComboBox<String> roleCombo;
    @FXML private VBox vinContainer;
    @FXML private TextField vinField;
    @FXML private VBox enforcerContainer;
    @FXML private Label enforcerIdLabel;
    @FXML private TextField enforcerIdField;
    @FXML private Label statusLabel;
    @FXML private Label statusDot;
    @FXML private Label serverUrlLabel;

    private boolean isPasswordVisible = false;

    /**
     * Callback consumer invoked when a user successfully signs up, passing their role.
     */
    public static java.util.function.Consumer<String> onSignup;

    /**
     * Initializes the JavaFX controller, populates the roles ComboBox, and configures field state change listeners.
     */
    @FXML
    public void initialize() {
        roleCombo.getItems().addAll("Customer", "PEO", "MO");
        roleCombo.setOnAction(e -> {
            String role = roleCombo.getValue();
            if ("Customer".equals(role)) {
                vinContainer.setVisible(true);
                vinContainer.setManaged(true);
                enforcerContainer.setVisible(false);
                enforcerContainer.setManaged(false);
            } else if ("PEO".equals(role)) {
                enforcerIdLabel.setText("Enforcer ID (9 digits) *");
                enforcerIdField.setPromptText("Enter 9-digit ID");
                enforcerContainer.setVisible(true);
                enforcerContainer.setManaged(true);
                vinContainer.setVisible(false);
                vinContainer.setManaged(false);
            } else if ("MO".equals(role)) {
                enforcerIdLabel.setText("Employee ID (9 digits) *");
                enforcerIdField.setPromptText("Enter 9-digit ID");
                enforcerContainer.setVisible(true);
                enforcerContainer.setManaged(true);
                vinContainer.setVisible(false);
                vinContainer.setManaged(false);
            } else {
                vinContainer.setVisible(false);
                vinContainer.setManaged(false);
                enforcerContainer.setVisible(false);
                enforcerContainer.setManaged(false);
            }
        });

        // Automatic VIN Formatting (123-45-678)
        vinField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isEmpty()) return;
            
            // Clean non-digits
            String cleaned = newValue.replaceAll("[^\\d]", "");
            
            // Limit to 8 digits (123-45-678 format is 8 digits total)
            if (cleaned.length() > 8) {
                cleaned = cleaned.substring(0, 8);
            }
            
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < cleaned.length(); i++) {
                if (i == 3 || i == 5) {
                    formatted.append("-");
                }
                formatted.append(cleaned.charAt(i));
            }
            
            String result = formatted.toString();
            if (!result.equals(newValue)) {
                javafx.application.Platform.runLater(() -> {
                    vinField.setText(result);
                    vinField.positionCaret(result.length());
                });
            }
        });

        // 9-digit restriction on Enforcer/Employee ID Field
        enforcerIdField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isEmpty()) return;
            
            // Clean non-digits
            String cleaned = newValue.replaceAll("[^\\d]", "");
            
            // Limit to 9 digits
            if (cleaned.length() > 9) {
                cleaned = cleaned.substring(0, 9);
            }
            
            if (!cleaned.equals(newValue)) {
                final String result = cleaned;
                javafx.application.Platform.runLater(() -> {
                    enforcerIdField.setText(result);
                    enforcerIdField.positionCaret(result.length());
                });
            }
        });

        // Clear error highlights on input change
        fullNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                fullNameField.getStyleClass().remove("field-error");
            }
        });
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
        vinField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                vinField.getStyleClass().remove("field-error");
            }
        });
        enforcerIdField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                enforcerIdField.getStyleClass().remove("field-error");
            }
        });
        roleCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                roleCombo.getStyleClass().remove("field-error");
            }
        });
    }

    /**
     * Handles keyboard events (like pressing ENTER) to trigger the signup action.
     *
     * @param event the keyboard event
     */
    @FXML
    public void handleKeyPress(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            handleSignup();
        }
    }

    /**
     * Validates input fields, verifies username uniqueness, registers the user, and triggers success handlers.
     */
    @FXML
    public void handleSignup() {
        String role = roleCombo.getValue();
        String username = usernameField.getText();
        if (username != null) username = username.trim();
        String password = isPasswordVisible ? passwordTextField.getText() : passwordField.getText();
        if (password != null) password = password.trim();
        String fullName = fullNameField.getText();
        if (fullName != null) fullName = fullName.trim();
        String vin = "";

        boolean hasError = false;

        roleCombo.getStyleClass().remove("field-error");
        fullNameField.getStyleClass().remove("field-error");
        usernameField.getStyleClass().remove("field-error");
        passwordField.getStyleClass().remove("field-error");
        passwordTextField.getStyleClass().remove("field-error");
        vinField.getStyleClass().remove("field-error");
        enforcerIdField.getStyleClass().remove("field-error");

        if (fullName == null || fullName.trim().isEmpty()) {
            fullNameField.getStyleClass().add("field-error");
            hasError = true;
        }

        if (role == null || role.isEmpty()) {
            roleCombo.getStyleClass().add("field-error");
            hasError = true;
        }

        if (username == null || username.trim().isEmpty()) {
            usernameField.getStyleClass().add("field-error");
            hasError = true;
        }

        if (password == null || password.trim().isEmpty()) {
            passwordField.getStyleClass().add("field-error");
            passwordTextField.getStyleClass().add("field-error");
            hasError = true;
        }

        if ("Customer".equals(role)) {
            vin = vinField.getText();
            if (vin == null || vin.trim().isEmpty()) {
                vinField.getStyleClass().add("field-error");
                hasError = true;
            }
        } else if ("PEO".equals(role) || "MO".equals(role)) {
            vin = enforcerIdField.getText();
            if (vin == null || vin.trim().isEmpty()) {
                enforcerIdField.getStyleClass().add("field-error");
                hasError = true;
            }
        }

        if (hasError) {
            setStatus("Please fill in all empty fields.", true);
            return;
        }

        // Duplicate username validation locally & network-wide!
        boolean userExists = LoginController.signupPasswords.containsKey(username);
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
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (userExists) {
            usernameField.getStyleClass().add("field-error");
            setStatus("Username already exists. Please choose another.", true);
            return;
        }

        // Save in static in-memory map
        LoginController.signupPasswords.put(username, password);
        LoginController.signupRoles.put(username, role);
        if (vin != null && !vin.isEmpty()) {
            LoginController.signupVins.put(username, vin);
        }

        // Save locally to properties file
        LoginController.saveUserToFile(username, password, role, vin);

        // Save in MongoDB collection in a background thread to sync with other network computers!
        final String finalVin = vin;
        final String finalUsername = username;
        final String finalPassword = password;
        final String finalRole = role;
        new Thread(() -> {
            try {
                edu.kinneret.parking.common.AppConfig config = edu.kinneret.parking.common.AppConfig.fromEnvironment(edu.kinneret.parking.common.AppConfig.ApplicationProfile.MO_UI);
                try (edu.kinneret.parking.common.ParkingRepository repo = new edu.kinneret.parking.common.ParkingRepository(config)) {
                    repo.registerUser(finalUsername, finalPassword, finalRole, finalVin);
                }
            } catch (Exception e) {
                System.err.println("Error registering user on network database: " + e.getMessage());
            }
        }).start();

        LoginController.currentUsername = username;
        setStatus("Account created! Logging in...", false);
        
        if (onSignup != null) {
            onSignup.accept(role);
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
            togglePasswordBtn.setStyle("-fx-opacity: 0.5;");
        } else {
            passwordField.setText(passwordTextField.getText());
            passwordField.setVisible(true);
            passwordTextField.setVisible(false);
            togglePasswordBtn.setStyle("-fx-opacity: 1.0;");
        }
    }

    /**
     * Redirects the user interface from the Signup view to the Login view.
     */
    @FXML
    public void handleLoginRedirect() {
        try {
            Stage stage = (Stage) statusLabel.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/LoginView.fxml"));
            Parent loginRoot = loader.load();
            Scene loginScene = PhoneFrameBuilder.createScaledScene(loginRoot, stage);
            loginScene.getStylesheets().add(getClass().getResource("/auth-style.css").toExternalForm());
            stage.setScene(loginScene);
        } catch (Exception e) {
            setStatus("Error navigating to login.", true);
        }
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setStyle(error ? "-fx-text-fill: #ef4444;" : "-fx-text-fill: #4ade80;");
    }
}

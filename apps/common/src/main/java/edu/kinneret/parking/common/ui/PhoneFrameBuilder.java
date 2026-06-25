package edu.kinneret.parking.common.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Builder utility that wraps JavaFX layout contents inside a custom mobile
 * phone frame styling.
 */
public class PhoneFrameBuilder {

    private static double xOffset = 0;
    private static double yOffset = 0;

    /** Utility/builder class; instantiation is not permitted. */
    private PhoneFrameBuilder() {
        // Prevent instantiation of utility class
    }

    /**
     * Wraps the given node content in a stylized mobile phone frame with hardware
     * mockups and stage dragging handlers.
     *
     * @param content the UI node content to wrap
     * @param stage   the JavaFX primary stage containing this frame
     * @return the root layout pane enclosing the stylized phone frame
     */
    public static StackPane wrapInPhoneFrame(Node content, Stage stage) {
        // 1. Phone Bezel
        Rectangle bezel = new Rectangle(410, 864);
        bezel.setArcWidth(80);
        bezel.setArcHeight(80);
        bezel.setFill(Color.web("#0f172a"));
        bezel.setStroke(Color.web("#1e293b"));
        bezel.setStrokeWidth(4);

        // 2. Content Area
        StackPane contentArea = new StackPane(content);
        contentArea.getStyleClass().add("main-container");
        contentArea.setPrefSize(390, 844);
        contentArea.setMaxSize(390, 844);

        Rectangle clip = new Rectangle(390, 844);
        clip.setArcWidth(80);
        clip.setArcHeight(80);
        contentArea.setClip(clip);

        // 3. Hardware Details
        Rectangle notch = new Rectangle(140, 30);
        notch.setArcWidth(25);
        notch.setArcHeight(25);
        notch.setFill(Color.BLACK);

        Rectangle homeBar = new Rectangle(140, 5);
        homeBar.setArcWidth(10);
        homeBar.setArcHeight(10);
        homeBar.setFill(Color.web("#ffffff", 0.3));

        // Side Buttons
        VBox sideButtons = new VBox(20);
        sideButtons.setAlignment(Pos.CENTER_LEFT);
        sideButtons.setTranslateX(-5);
        sideButtons.setTranslateY(200);

        Rectangle volUp = new Rectangle(3, 40);
        volUp.setFill(Color.web("#334155"));
        Rectangle volDown = new Rectangle(3, 40);
        volDown.setFill(Color.web("#334155"));
        sideButtons.getChildren().addAll(volUp, volDown);

        Rectangle powerBtn = new Rectangle(3, 60);
        powerBtn.setFill(Color.web("#ef4444"));
        powerBtn.setTranslateX(408);
        powerBtn.setTranslateY(230);

        // Assembly
        StackPane frameContent = new StackPane();
        frameContent.setPrefSize(410, 864);

        frameContent.getChildren().add(contentArea);
        StackPane.setAlignment(contentArea, Pos.CENTER);

        frameContent.getChildren().add(notch);
        StackPane.setAlignment(notch, Pos.TOP_CENTER);
        StackPane.setMargin(notch, new Insets(10, 0, 0, 0));

        frameContent.getChildren().add(homeBar);
        StackPane.setAlignment(homeBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(homeBar, new Insets(0, 0, 15, 0));

        StackPane phoneBody = new StackPane();
        phoneBody.getChildren().addAll(bezel, sideButtons, powerBtn, frameContent);
        phoneBody.setEffect(new javafx.scene.effect.DropShadow(30, Color.BLACK));

        // Dragging
        phoneBody.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        phoneBody.setOnMouseDragged(event -> {
            if (stage != null) {
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            }
        });

        powerBtn.setOnMouseClicked(e -> {
            Platform.exit();
            System.exit(0);
        });
        powerBtn.setCursor(javafx.scene.Cursor.HAND);

        StackPane root = new StackPane(phoneBody);
        root.setPadding(new Insets(0, 30, 0, 30));
        root.setBackground(Background.EMPTY);

        return root;
    }

    /**
     * Creates a scaled Scene containing the given content wrapped in the phone frame.
     * The scene scale is calculated dynamically to occupy 100% of the screen height.
     *
     * @param content the layout content of the phone screen
     * @param stage   the JavaFX Stage window context
     * @return the scaled Scene instance
     */
    public static Scene createScaledScene(Node content, Stage stage) {
        Rectangle2D bounds = Screen.getPrimary().getBounds();
        double targetHeight = bounds.getHeight();
        double scaleFactor = targetHeight / 864.0;
        double targetWidth = 470.0 * scaleFactor;

        if (stage != null) {
            stage.setMinWidth(targetWidth);
            stage.setMinHeight(targetHeight);
            stage.setMaxWidth(targetWidth);
            stage.setMaxHeight(targetHeight);
            stage.setX((bounds.getWidth() - targetWidth) / 2.0);
            stage.setY(0.0);
        }

        StackPane framed = wrapInPhoneFrame(content, stage);
        Group scaleGroup = new Group(framed);
        scaleGroup.getTransforms().add(new Scale(scaleFactor, scaleFactor));

        Scene scene = new Scene(scaleGroup, targetWidth, targetHeight);
        scene.setFill(Color.TRANSPARENT);
        return scene;
    }
}

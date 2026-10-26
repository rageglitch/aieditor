package com.example;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.UnaryOperator;

public class Main extends Application {

    private BufferedImage original;
    private BufferedImage current;
    private BufferedImage previewBase;
    private final Deque<BufferedImage> undoStack = new ArrayDeque<>();
    private final Deque<BufferedImage> redoStack = new ArrayDeque<>();

    private final ImageView view = new ImageView();
    private final Rectangle cropRect = new Rectangle();
    private final Label status = new Label("Ready");
    private final Label dims = new Label("");
    private final Label zoomLabel = new Label("100%");
    private final Label hint = new Label("Drop an image here or press Ctrl+O");
    private final Slider zoom = new Slider(0.05, 4, 1);
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Slider sBright = new Slider(-100, 100, 0);
    private final Slider sContrast = new Slider(-100, 100, 0);
    private final Slider sSat = new Slider(-100, 100, 0);
    private final ToggleButton cropToggle = new ToggleButton("Crop");
    private final ColorPicker bgPicker = new ColorPicker(Color.WHITE);
    private final Button removeBtn = new Button("Remove Background");
    private ScrollPane scroll;
    private Stage stage;

    private boolean dark = true;
    private boolean silent = false;
    private double cropStartX, cropStartY;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        view.setPreserveRatio(true);
        view.setSmooth(true);

        cropRect.setManaged(false);
        cropRect.setVisible(false);
        cropRect.setMouseTransparent(true);
        cropRect.setFill(Color.web("#3b82f640"));
        cropRect.setStroke(Color.web("#3b82f6"));
        cropRect.setStrokeWidth(2);
        cropRect.getStrokeDashArray().addAll(8.0, 6.0);

        setupCropMouse();

        BorderPane root = new BorderPane();
        root.setTop(new VBox(buildMenuBar(), buildToolBar()));
        root.setLeft(buildSidebar());
        root.setCenter(buildCanvas());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (!files.isEmpty()) {
                loadFile(files.get(0));
            }
            e.setDropCompleted(true);
            e.consume();
        });

        stage.setTitle("AI Image Editor");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
    }

    // ---------------- UI construction ----------------

    private MenuBar buildMenuBar() {
        MenuItem open = item("Open...", KeyCode.O, this::chooseFile);
        MenuItem save = item("Save As...", KeyCode.S, this::saveImage);
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        Menu file = new Menu("File", null, open, save, new SeparatorMenuItem(), exit);

        MenuItem undo = item("Undo", KeyCode.Z, this::undo);
        MenuItem redo = item("Redo", KeyCode.Y, this::redo);
        MenuItem reset = new MenuItem("Reset to original");
        reset.setOnAction(e -> resetToOriginal());
        Menu edit = new Menu("Edit", null, undo, redo, new SeparatorMenuItem(), reset);

        MenuItem fit = item("Fit to window", KeyCode.DIGIT0, this::fitToWindow);
        MenuItem theme = new MenuItem("Switch light / dark theme");
        theme.setOnAction(e -> toggleTheme());
        Menu viewMenu = new Menu("View", null, fit, theme);

        return new MenuBar(file, edit, viewMenu);
    }

    private MenuItem item(String text, KeyCode key, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setAccelerator(new KeyCodeCombination(key, KeyCombination.CONTROL_DOWN));
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private ToolBar buildToolBar() {
        Button open = btn("Open", this::chooseFile);
        Button save = btn("Save", this::saveImage);
        Button undo = btn("\u21B6 Undo", this::undo);
        Button redo = btn("\u21B7 Redo", this::redo);
        Button reset = btn("Reset", this::resetToOriginal);
        Button fit = btn("Fit", this::fitToWindow);
        Button theme = btn("\u25D0 Theme", this::toggleTheme);

        Button compare = new Button("Hold to compare");
        compare.setOnMousePressed(e -> {
            if (original != null) {
                view.setImage(SwingFXUtils.toFXImage(original, null));
            }
        });
        compare.setOnMouseReleased(e -> {
            if (current != null) {
                view.setImage(SwingFXUtils.toFXImage(current, null));
            }
        });

        open.getStyleClass().add("accent");
        return new ToolBar(
            open, save,
            new Separator(Orientation.VERTICAL),
            undo, redo, reset,
            new Separator(Orientation.VERTICAL),
            compare, fit, theme
        );
    }

    private ScrollPane buildSidebar() {
        VBox panels = new VBox(
            pane("Adjust", adjustBox(), true),
            pane("Filters", filtersBox(), true),
            pane("Transform", transformBox(), true),
            pane("AI Tools", aiBox(), true)
        );
        ScrollPane side = new ScrollPane(panels);
        side.setFitToWidth(true);
        side.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        side.setPrefWidth(290);
        side.setMinWidth(290);
        side.getStyleClass().add("sidebar");
        return side;
    }

    private TitledPane pane(String title, Node content, boolean expanded) {
        TitledPane tp = new TitledPane(title, content);
        tp.setAnimated(false);
        tp.setExpanded(expanded);
        return tp;
    }

    private VBox adjustBox() {
        for (Slider s : new Slider[]{sBright, sContrast, sSat}) {
            s.valueProperty().addListener((o, a, b) -> previewAdjust());
        }
        Button apply = btn("Apply", this::applyAdjust);
        apply.getStyleClass().add("accent");
        Button reset = btn("Reset", this::resetAdjust);
        apply.setMaxWidth(Double.MAX_VALUE);
        reset.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(apply, Priority.ALWAYS);
        HBox.setHgrow(reset, Priority.ALWAYS);

        VBox box = new VBox(12,
            sliderRow("Brightness", sBright),
            sliderRow("Contrast", sContrast),
            sliderRow("Saturation", sSat),
            new HBox(8, apply, reset));
        box.setPadding(new Insets(10));
        return box;
    }

    private VBox sliderRow(String name, Slider s) {
        Label val = new Label();
        val.textProperty().bind(Bindings.format("%.0f", s.valueProperty()));
        val.getStyleClass().add("text-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new VBox(4, new HBox(new Label(name), spacer, val), s);
    }

    private GridPane filtersBox() {
        return grid(
            btn("Grayscale", () -> apply(ImageOps::grayscale)),
            btn("Sepia", () -> apply(ImageOps::sepia)),
            btn("Invert", () -> apply(ImageOps::invert)),
            btn("Blur", () -> apply(i -> ImageOps.blur(i, 2))),
            btn("Sharpen", () -> apply(ImageOps::sharpen)),
            btn("Pixelate", () -> apply(i -> ImageOps.pixelate(i, Math.max(4, i.getWidth() / 80))))
        );
    }

    private GridPane transformBox() {
        cropToggle.setOnAction(e -> {
            boolean on = cropToggle.isSelected();
            view.setCursor(on ? Cursor.CROSSHAIR : Cursor.DEFAULT);
            if (on) {
                status.setText("Drag on the image to select an area, then click Apply Crop");
            } else {
                cropRect.setVisible(false);
            }
        });
        Button applyCrop = btn("Apply Crop", this::applyCrop);
        applyCrop.getStyleClass().add("accent");

        return grid(
            btn("\u27F2 Rotate left", () -> apply(i -> ImageOps.rotate(i, false))),
            btn("\u27F3 Rotate right", () -> apply(i -> ImageOps.rotate(i, true))),
            btn("Flip horizontal", () -> apply(i -> ImageOps.flip(i, true))),
            btn("Flip vertical", () -> apply(i -> ImageOps.flip(i, false))),
            cropToggle,
            applyCrop,
            btn("Resize...", this::resizeImage)
        );
    }

    private VBox aiBox() {
        removeBtn.setMaxWidth(Double.MAX_VALUE);
        removeBtn.getStyleClass().add("accent");
        removeBtn.setOnAction(e -> removeBackground());

        Label fillLabel = new Label("Fill transparent areas with a colour");
        fillLabel.getStyleClass().add("text-muted");
        Button fill = btn("Fill", this::fillBackground);
        HBox fillRow = new HBox(8, bgPicker, fill);

        VBox box = new VBox(10, removeBtn, new Separator(), fillLabel, fillRow);
        box.setPadding(new Insets(10));
        return box;
    }

    private GridPane grid(Node... nodes) {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        g.setPadding(new Insets(10));
        ColumnConstraints c1 = new ColumnConstraints();
        ColumnConstraints c2 = new ColumnConstraints();
        c1.setPercentWidth(50);
        c2.setPercentWidth(50);
        g.getColumnConstraints().addAll(c1, c2);
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] instanceof Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
            }
            GridPane.setHgrow(nodes[i], Priority.ALWAYS);
            g.add(nodes[i], i % 2, i / 2);
        }
        return g;
    }

    private StackPane buildCanvas() {
        Pane imageBox = new Pane(view, cropRect);
        imageBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        imageBox.setBackground(checkerboard());

        StackPane holder = new StackPane(imageBox);
        holder.setPadding(new Insets(24));

        scroll = new ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("canvas");

        busy.setVisible(false);
        busy.setMaxSize(56, 56);

        hint.getStyleClass().addAll("title-3", "text-muted");
        hint.setMouseTransparent(true);

        return new StackPane(scroll, hint, busy);
    }

    private Background checkerboard() {
        WritableImage img = new WritableImage(16, 16);
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean light = ((x / 8) + (y / 8)) % 2 == 0;
                pw.setColor(x, y, Color.web(light ? "#5a5a5a" : "#4a4a4a"));
            }
        }
        return new Background(new BackgroundImage(img,
            BackgroundRepeat.REPEAT, BackgroundRepeat.REPEAT,
            BackgroundPosition.DEFAULT, BackgroundSize.DEFAULT));
    }

    private HBox buildStatusBar() {
        zoom.setPrefWidth(160);
        zoom.valueProperty().addListener((o, a, b) -> updateZoom());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        status.getStyleClass().add("text-muted");
        dims.getStyleClass().add("text-muted");
        HBox bar = new HBox(14, status, spacer, dims, new Separator(Orientation.VERTICAL), new Label("Zoom"), zoom, zoomLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ---------------- Crop selection ----------------

    private void setupCropMouse() {
        view.setOnMousePressed(e -> {
            if (!cropToggle.isSelected() || current == null) {
                return;
            }
            cropStartX = clampTo(e.getX(), view.getBoundsInLocal().getWidth());
            cropStartY = clampTo(e.getY(), view.getBoundsInLocal().getHeight());
            cropRect.setX(cropStartX);
            cropRect.setY(cropStartY);
            cropRect.setWidth(0);
            cropRect.setHeight(0);
            cropRect.setVisible(true);
        });
        view.setOnMouseDragged(e -> {
            if (!cropToggle.isSelected() || current == null) {
                return;
            }
            double x = clampTo(e.getX(), view.getBoundsInLocal().getWidth());
            double y = clampTo(e.getY(), view.getBoundsInLocal().getHeight());
            cropRect.setX(Math.min(cropStartX, x));
            cropRect.setY(Math.min(cropStartY, y));
            cropRect.setWidth(Math.abs(x - cropStartX));
            cropRect.setHeight(Math.abs(y - cropStartY));
        });
    }

    private double clampTo(double v, double max) {
        return Math.max(0, Math.min(v, max));
    }

    private void applyCrop() {
        if (current == null || !cropRect.isVisible()) {
            status.setText("Select an area with the Crop tool first");
            return;
        }
        double scale = current.getWidth() / view.getBoundsInLocal().getWidth();
        int x = (int) (cropRect.getX() * scale);
        int y = (int) (cropRect.getY() * scale);
        int w = (int) (cropRect.getWidth() * scale);
        int h = (int) (cropRect.getHeight() * scale);
        if (w < 2 || h < 2) {
            status.setText("Selection is too small");
            return;
        }
        apply(i -> ImageOps.crop(i, x, y, w, h));
        cropRect.setVisible(false);
        cropToggle.setSelected(false);
        view.setCursor(Cursor.DEFAULT);
        status.setText("Cropped");
    }

    // ---------------- Adjust sliders ----------------

    private void previewAdjust() {
        if (current == null || silent) {
            return;
        }
        if (previewBase == null) {
            previewBase = ImageOps.fit(current, 900);
        }
        BufferedImage p = ImageOps.adjust(previewBase, sBright.getValue(), sContrast.getValue(), sSat.getValue());
        view.setImage(SwingFXUtils.toFXImage(p, null));
    }

    private void applyAdjust() {
        if (current == null) {
            return;
        }
        double b = sBright.getValue(), c = sContrast.getValue(), s = sSat.getValue();
        if (b == 0 && c == 0 && s == 0) {
            return;
        }
        apply(i -> ImageOps.adjust(i, b, c, s));
        status.setText("Adjustments applied");
    }

    private void resetAdjust() {
        resetSliders();
        if (current != null) {
            view.setImage(SwingFXUtils.toFXImage(current, null));
        }
    }

    private void resetSliders() {
        silent = true;
        sBright.setValue(0);
        sContrast.setValue(0);
        sSat.setValue(0);
        silent = false;
    }

    // ---------------- Core editing actions ----------------

    private void apply(UnaryOperator<BufferedImage> op) {
        if (current == null) {
            status.setText("Open an image first");
            return;
        }
        pushUndo();
        current = op.apply(current);
        show();
    }

    private void pushUndo() {
        undoStack.push(current);
        while (undoStack.size() > 30) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(current);
        current = undoStack.pop();
        show();
        status.setText("Undone");
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(current);
        current = redoStack.pop();
        show();
        status.setText("Redone");
    }

    private void resetToOriginal() {
        if (original == null || current == original) {
            return;
        }
        pushUndo();
        current = original;
        show();
        status.setText("Reset to original");
    }

    private void resizeImage() {
        if (current == null) {
            return;
        }
        TextInputDialog d = new TextInputDialog("50");
        d.setTitle("Resize");
        d.setHeaderText("Resize image");
        d.setContentText("New size (% of current):");
        d.showAndWait().ifPresent(txt -> {
            try {
                int pct = Integer.parseInt(txt.trim());
                if (pct < 1 || pct > 400) {
                    throw new NumberFormatException();
                }
                apply(i -> ImageOps.resize(i, i.getWidth() * pct / 100, i.getHeight() * pct / 100));
            } catch (NumberFormatException ex) {
                status.setText("Enter a number from 1 to 400");
            }
        });
    }

    private void fillBackground() {
        Color c = bgPicker.getValue();
        int rgb = ((int) Math.round(c.getRed() * 255) << 16)
            | ((int) Math.round(c.getGreen() * 255) << 8)
            | (int) Math.round(c.getBlue() * 255);
        apply(i -> ImageOps.fillBackground(i, rgb));
        status.setText("Background filled");
    }

    private void removeBackground() {
        if (current == null) {
            status.setText("Open an image first");
            return;
        }
        busy.setVisible(true);
        removeBtn.setDisable(true);
        status.setText("Removing background...");
        BufferedImage source = current;
        new Thread(() -> {
            try {
                BufferedImage result = AiClient.removeBackground(source);
                Platform.runLater(() -> {
                    pushUndo();
                    current = result;
                    show();
                    finishAi("Background removed");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> finishAi("AI error: " + ex.getMessage()));
            }
        }).start();
    }

    private void finishAi(String message) {
        busy.setVisible(false);
        removeBtn.setDisable(false);
        status.setText(message);
    }

    // ---------------- Display, zoom, files, theme ----------------

    private void show() {
        previewBase = null;
        resetSliders();
        view.setImage(SwingFXUtils.toFXImage(current, null));
        hint.setVisible(false);
        dims.setText(current.getWidth() + " x " + current.getHeight() + " px");
        updateZoom();
    }

    private void updateZoom() {
        zoomLabel.setText(Math.round(zoom.getValue() * 100) + "%");
        if (current != null) {
            view.setFitWidth(current.getWidth() * zoom.getValue());
        }
    }

    private void fitToWindow() {
        if (current == null || scroll == null) {
            return;
        }
        Bounds vb = scroll.getViewportBounds();
        double z = Math.min((vb.getWidth() - 60) / current.getWidth(),
            (vb.getHeight() - 60) / current.getHeight());
        zoom.setValue(Math.max(zoom.getMin(), Math.min(zoom.getMax(), z)));
    }

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        File f = fc.showOpenDialog(stage);
        if (f != null) {
            loadFile(f);
        }
    }

    private void loadFile(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                status.setText("Unsupported file: " + f.getName());
                return;
            }
            original = ImageOps.copy(img);
            current = original;
            undoStack.clear();
            redoStack.clear();
            cropRect.setVisible(false);
            cropToggle.setSelected(false);
            show();
            Platform.runLater(this::fitToWindow);
            status.setText("Opened " + f.getName());
            stage.setTitle(f.getName() + " - AI Image Editor");
        } catch (Exception ex) {
            status.setText("Error: " + ex.getMessage());
        }
    }

    private void saveImage() {
        if (current == null) {
            return;
        }
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("PNG image (keeps transparency)", "*.png"),
            new FileChooser.ExtensionFilter("JPEG image", "*.jpg"));
        File f = fc.showSaveDialog(stage);
        if (f == null) {
            return;
        }
        String name = f.getName().toLowerCase();
        try {
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                ImageIO.write(ImageOps.flatten(current, 0xFFFFFF), "jpg", f);
            } else {
                if (!name.endsWith(".png")) {
                    f = new File(f.getPath() + ".png");
                }
                ImageIO.write(current, "png", f);
            }
            status.setText("Saved " + f.getName());
        } catch (Exception ex) {
            status.setText("Error: " + ex.getMessage());
        }
    }

    private void toggleTheme() {
        dark = !dark;
        Application.setUserAgentStylesheet(dark
            ? new PrimerDark().getUserAgentStylesheet()
            : new PrimerLight().getUserAgentStylesheet());
    }

    private Button btn(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }
}
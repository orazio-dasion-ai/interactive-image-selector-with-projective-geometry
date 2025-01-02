package selector;

import static selector.SelectionModel.SelectionState.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.xml.stream.StreamFilter;
import selector.SelectionModel.SelectionState;
import scissors.ScissorsSelectionModel;

/**
 * A graphical application for selecting and extracting regions of images.
 */
public class SelectorApp implements PropertyChangeListener {

    /**
     * Our application window.  Disposed when application exits.
     */
    private final JFrame frame;

    /**
     * Component for displaying the current image and selection tool.
     */
    private final ImagePanel imgPanel;

    /**
     * The current state of the selection tool.  Must always match the model used by `imgPanel`.
     */
    private SelectionModel model;

    /* Components whose state must be changed during the selection process. */
    private JMenuItem saveItem;
    private JMenuItem undoItem;
    private JMenuItem deleteItem;
    private JMenuItem fillItem;

    private JButton cancelButton;
    private JButton undoButton;
    private JButton resetButton;
    private JButton finishButton;
    private JButton deleteButton;
    private final JLabel statusLabel;

    // New in A6
    /**
     * Progress bar to indicate the progress of a model that needs to do long calculations in a
     * PROCESSING state.
     */
    private JProgressBar processingProgress;


    /**
     * Construct a new application instance.  Initializes GUI components, so must be invoked on the
     * Swing Event Dispatch Thread.  Does not show the application window (call `start()` to do
     * that).
     */
    public SelectorApp() {
        // Initialize application window
        frame = new JFrame("Selector");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // Add status bar
        statusLabel = new JLabel();

        // Put status label on the bottom of window using BorderLayout
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(statusLabel, BorderLayout.PAGE_END);
        // Add image component with scrollbars
        imgPanel = new ImagePanel();

        // Create scroll pane and add img panel
        JScrollPane scrollPane = new JScrollPane(imgPanel);
        //frame.add(imgPanel);  // Replace this line
        // Set the dimensions of the scroll pane to 500,500
        scrollPane.setPreferredSize(new Dimension(500,500));
        // Put the scroll pane in the center of the frame
        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);

        // Add menu bar
        frame.setJMenuBar(makeMenuBar());

        // Add control buttons

        JPanel controlPanel = makeControlPanel();
        frame.getContentPane().add(controlPanel, BorderLayout.LINE_END);

        // New in A6: Add progress bar
        processingProgress = new JProgressBar();
        frame.add(processingProgress, BorderLayout.PAGE_START);

        // Controller: Set initial selection tool and update components to reflect its state
        setSelectionModel(new PointToPointSelectionModel(true));
    }

    /**
     * Create and populate a menu bar with our application's menus and items and attach listeners.
     * Should only be called from constructor, as it initializes menu item fields.
     */
    private JMenuBar makeMenuBar() {
        JMenuBar menuBar = new JMenuBar();


        // Create and populate File menu
        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);
        JMenuItem openItem = new JMenuItem("Open...");
        fileMenu.add(openItem);
        saveItem = new JMenuItem("Save...");
        fileMenu.add(saveItem);
        JMenuItem closeItem = new JMenuItem("Close");
        fileMenu.add(closeItem);
        JMenuItem exitItem = new JMenuItem("Exit");
        fileMenu.add(exitItem);
        JMenu editMenu = new JMenu("Edit");
        menuBar.add(editMenu);
        JMenuItem deleteItem = new JMenu("Delete Selected Region");
        editMenu.add(deleteItem);
        fillItem = new JMenuItem("Fill with Color...");
        editMenu.add(fillItem);
        fillItem.addActionListener(e -> doFillWithColor());


        // Create and populate Edit menu
        undoItem = new JMenuItem("Undo");
        editMenu.add(undoItem);




        // Controller: Attach menu item listeners
        openItem.addActionListener(e -> openImage());
        closeItem.addActionListener(e -> imgPanel.setImage(null));
        saveItem.addActionListener(e -> saveSelection());
        exitItem.addActionListener(e -> frame.dispose());
        undoItem.addActionListener(e -> model.undo());
        deleteItem.addActionListener(e -> {
            if (model.state() == SelectionModel.SelectionState.SELECTED) {
                model.deleteSelectedRegion();
            }
        });
        return menuBar;
    }

    /**
     * Called when user clicks "Fill with Color..."
     */
    private void doFillWithColor() {
        // Only do something if we have a valid selection
        if (model.state() != SelectionModel.SelectionState.SELECTED) {
            JOptionPane.showMessageDialog(frame,
                    "You must finish a selection before filling it with color.",
                    "No selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Show color chooser dialog
        Color chosenColor = JColorChooser.showDialog(frame, "Pick a color", Color.RED);
        if (chosenColor != null) {
            // Fill the region
            model.fillSelectionWithColor(chosenColor);
            // Possibly repaint or rely on property changes
            imgPanel.repaint();
        }
    }


    /**
     * Return a panel containing buttons for controlling image selection.  Should only be called
     * from constructor, as it initializes button fields.
     */
    private JPanel makeControlPanel() {
        JPanel p = new JPanel(new BorderLayout());
        GridLayout layout = new GridLayout(0,1);
        p.setLayout(layout);

        JComboBox<String> selectionModelComboBox = new JComboBox<>(new String[]{"Point-to-point",
                "Intelligent scissors: gray" });

        //p.add(selectionModelComboBox);
        selectionModelComboBox.addActionListener(e -> {

            String selectedModel = (String) selectionModelComboBox.getSelectedItem();
            SelectionModel newModel = null;

            if (selectedModel.equals("Point-to-point")) {
                newModel = new PointToPointSelectionModel(model);
            } else if (selectedModel.equals("Intelligent scissors: gray")) {
                newModel = new ScissorsSelectionModel("CrossGradMono", model);
            }

            setSelectionModel(newModel);
        });

        cancelButton = new JButton("Cancel");
        p.add(cancelButton);
        cancelButton.addActionListener(e -> model.cancelProcessing());

        undoButton = new JButton("Undo");
        p.add(undoButton);
        undoButton.addActionListener(e -> model.undo());

        resetButton = new JButton("Reset");
        p.add(resetButton);
        resetButton.addActionListener(e -> model.reset());

        finishButton = new JButton("Finish");
        p.add(finishButton);
        finishButton.addActionListener(e -> model.finishSelection());

        deleteButton = new JButton("Delete Selection");
        p.add(deleteButton);
        deleteButton.setEnabled(false);

        String[] comBoxOptions = new String[]{"Point-to-point",
                "Intelligent scissors","CrossGradColor"};

        JComboBox comBox = new JComboBox(comBoxOptions);

        p.add(comBox);

        comBox.addActionListener(e -> {
            String selectedModel = (String) comBox.getSelectedItem();
            SelectionModel newModel = null;

            if (selectedModel.equals("Point-to-point")) {
                newModel = new PointToPointSelectionModel(model);
            } else if (selectedModel.equals("Intelligent scissors")) {
                newModel = new ScissorsSelectionModel("CrossGradMono", model);
            } else if (selectedModel.equals("CrossGradColor")){
                newModel = new ScissorsSelectionModel("CrossGradColor", model);
            }
            setSelectionModel(newModel);}
        );

        deleteButton.addActionListener(e -> {
            if (model.state() == SelectionState.SELECTED) {
                model.deleteSelectedRegion();
            }
        });
        return p;
    }

    /**
     * Start the application by showing its window.
     */
    public void start() {
        // Compute ideal window size
        frame.pack();

        frame.setVisible(true);
    }

    /**
     * React to property changes in an observed model.  Supported properties include:
     * * "state": Update components to reflect the new selection state.
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if ("state".equals(evt.getPropertyName())) {
            reflectSelectionState(model.state());

            SelectionState newState = (SelectionState) evt.getNewValue();

            if (newState == PROCESSING) {
                processingProgress.setValue(0);
                processingProgress.setIndeterminate(true);
            } else {
                processingProgress.setValue(0);
                processingProgress.setIndeterminate(false);
            }

        } else if ("progress".equals(evt.getPropertyName())) {
            int progress = (int) evt.getNewValue();

            processingProgress.setValue(progress);
            processingProgress.setIndeterminate(false);
        }
    }

    /**
     * Update components to reflect a selection state of `state`.  Disable buttons and menu items
     * whose actions are invalid in that state, and update the status bar.
     */
    private void reflectSelectionState(SelectionState state) {
        // Update status bar to show current state
        statusLabel.setText(state.toString());

        if (state == SelectionState.PROCESSING){
            cancelButton.setEnabled(true);
            undoButton.setEnabled(false);
            resetButton.setEnabled(false);
            finishButton.setEnabled(false);
            if (deleteButton != null) deleteButton.setEnabled(false);
            if (deleteItem != null) deleteItem.setEnabled(false);

        } else if (state == SelectionState.SELECTING) {
            cancelButton.setEnabled(false);
            undoButton.setEnabled(true);
            resetButton.setEnabled(true);
            finishButton.setEnabled(true);
            if (deleteButton != null) deleteButton.setEnabled(false);
            if (deleteItem != null) deleteItem.setEnabled(false);

        } else if (state == SelectionState.NO_SELECTION) {
            cancelButton.setEnabled(false);
            undoButton.setEnabled(false);
            resetButton.setEnabled(false);
            finishButton.setEnabled(false);
            if (deleteButton != null) deleteButton.setEnabled(false);
            if (deleteItem != null) deleteItem.setEnabled(false);

        } else if (state == SelectionState.SELECTED) {
            cancelButton.setEnabled(false);
            undoButton.setEnabled(true);
            resetButton.setEnabled(true);
            finishButton.setEnabled(false);
            deleteButton.setEnabled(true);
            if (deleteButton != null) deleteButton.setEnabled(true);
            // Enable menu item only if we have a valid reference to it
            if (deleteItem != null) deleteItem.setEnabled(true);

        } else {
            cancelButton.setEnabled(false);
            undoButton.setEnabled(false);
            resetButton.setEnabled(false);
            finishButton.setEnabled(false);
            deleteButton.setEnabled(false);
            if (deleteButton != null) deleteButton.setEnabled(false);
            if (deleteItem != null) deleteItem.setEnabled(false);
        }
    }

    /**
     * Return the model of the selection tool currently in use.
     */
    public SelectionModel getSelectionModel() {
        return model;
    }

    /**
     * Use `newModel` as the selection tool and update our view to reflect its state.  This
     * application will no longer respond to changes made to its previous selection model and will
     * instead respond to property changes from `newModel`.
     */
    public void setSelectionModel(SelectionModel newModel) {
        // Stop listening to old model
        if (model != null) {
            model.removePropertyChangeListener(this);
        }

        imgPanel.setSelectionModel(newModel);
        model = imgPanel.selection();
        model.addPropertyChangeListener("state", this);
        // New in A6: Listen for "progress" events
        model.addPropertyChangeListener("progress", this);

        // Since the new model's initial state may be different from the old model's state, manually
        //  trigger an update to our state-dependent view.
        reflectSelectionState(model.state());


    }

    /**
     * Start displaying and selecting from `img` instead of any previous image.  Argument may be
     * null, in which case no image is displayed and the current selection is reset.
     */
    public void setImage(BufferedImage img) {
        imgPanel.setImage(img);
    }

    /**
     * Allow the user to choose a new image from an "open" dialog.  If they do, start displaying and
     * selecting from that image.  Show an error message dialog (and retain any previous image) if
     * the chosen image could not be opened.
     */
    private void openImage() {
        JFileChooser chooser = new JFileChooser();
        // Start browsing in current directory
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        // Filter for file extensions supported by Java's ImageIO readers
        chooser.setFileFilter(new FileNameExtensionFilter("Image files",
                ImageIO.getReaderFileSuffixes()));

        int option = chooser.showOpenDialog(frame);

        if (option == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                BufferedImage image = ImageIO.read(file);

                if (image == null) {
                    throw new IllegalArgumentException("Could not read the image at " + file);
                }

                imgPanel.setImage(image);
                statusLabel.setText("Image loaded: " + file.getName());
            } catch (IOException | IllegalArgumentException e) {
                JOptionPane.showMessageDialog(frame,e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
                statusLabel.setText("Error: " + e.getMessage());
            }
        }
    }

    /**
     * Save the selected region of the current image to a file selected from a "save" dialog.
     * Show an error message dialog if the image could not be saved.
     */
    private void saveSelection() {
        JFileChooser chooser = new JFileChooser();
        // Start browsing in current directory
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        // We always save in PNG format, so only show existing PNG files
        chooser.setFileFilter(new FileNameExtensionFilter("PNG images", "png"));

        int option = chooser.showSaveDialog(frame);

        if (option == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            if (!file.getName().endsWith(".png")) {
                file = new File(file.getAbsolutePath() + ".png");
            }

            if (file.exists()) {
                int response = JOptionPane.showConfirmDialog(frame, "Do you want to overwrite"
                                + " this file?", "Confirm Overwrite", JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (response == JOptionPane.NO_OPTION || response == JOptionPane.CANCEL_OPTION) {
                    saveSelection();
                    return;
                }
            }
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                model.saveSelection(outputStream);

                try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                    outputStream.writeTo(fileOutputStream);
                }
            } catch (IOException | IllegalStateException e) {
                JOptionPane.showMessageDialog(frame, e.getMessage(),
                        e.getClass().getSimpleName(), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Run an instance of SelectorApp.  No program arguments are expected.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Set Swing theme to look the same (and less old) on all operating systems.
            try {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            } catch (Exception ignored) {
                /* If the Nimbus theme isn't available, just use the platform default. */
            }

            // Create and start the app
            SelectorApp app = new SelectorApp();
            app.start();
        });
    }
}

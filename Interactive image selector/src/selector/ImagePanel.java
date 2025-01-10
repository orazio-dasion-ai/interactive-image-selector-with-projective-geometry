package selector;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.SwingConstants;
import selector.ShapeUtils;


/**
 * A Swing component that displays an image and facilitates interaction with it
 * in order to select a region of the image. The image and selection model can both be
 * changed, and a placeholder label is shown if no valid image has been set.
 */
public class ImagePanel extends JPanel {

    /**
     * Label for drawing the image when a valid image has been set.
     */
    private final JLabel pic;

    /**
     * Component for interactively building a selection; must be placed on top of `pic` with their
     * upper-left corners aligned in order for coordinates within this component to match pixel
     * locations in `pic`.
     */
    private final SelectionComponent selector;

    /**
     * A CardLayout panel that switches between "no image loaded" placeholder and the image label.
     */
    private final JPanel cardPanel;
    private final CardLayout cardLayout;

    private Color currentShapeColor = Color.RED;


    /**
     * Construct a new ImagePanel with:
     * 1) A toolbar at the top for shape-pasting buttons.
     * 2) A CardLayout center area toggling between a placeholder and the image/selector.
     */
    public ImagePanel() {
        // Use BorderLayout so we can place a toolbar at the top (NORTH).
        super(new BorderLayout());

        // ====================
        // 1. CREATE A TOOLBAR
        // ====================
        JToolBar shapeToolBar = new JToolBar();
        add(shapeToolBar, BorderLayout.NORTH);

        // Example shapes: Circle, Square, Oval, Triangle
        // with corresponding colors
        String[] shapes = { "Circle", "Square", "Oval", "Triangle" };
        for (String shape : shapes) {
            JButton shapeButton = new JButton(shape);
            shapeButton.addActionListener(e -> {
                pasteShape(shape.toLowerCase(), 100, currentShapeColor);
            });
            shapeToolBar.add(shapeButton);
        }


        // =============================
        // 2. BUILD THE CARDLAYOUT AREA
        // =============================
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        add(cardPanel, BorderLayout.CENTER);

        // --- Placeholder label for "no image loaded"
        JLabel placeholder = new JLabel("No image loaded.", SwingConstants.CENTER);
        placeholder.setFont(placeholder.getFont().deriveFont(24f));
        cardPanel.add(placeholder, "placeholder");

        // --- Create our pic label for the actual image
        pic = new JLabel();
        pic.setHorizontalAlignment(SwingConstants.LEFT);
        pic.setVerticalAlignment(SwingConstants.TOP);

        // By default, let's use a simple selection model
        // (You can replace this with a ProjectiveSelectionModel if you prefer.)
        SelectionModel defaultModel = new PointToPointSelectionModel(true);
        selector = new SelectionComponent(defaultModel);

        // We'll place the `SelectionComponent` on top of `pic`
        // so that they align properly.
        pic.setLayout(new BorderLayout());
        pic.add(selector, BorderLayout.CENTER);

        // Add the pic label to the cardPanel
        cardPanel.add(pic, "image");

        // Initially, show the placeholder
        cardLayout.show(cardPanel, "placeholder");

        JButton pickColorButton = new JButton("Pick Color");
        pickColorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(
                    ImagePanel.this,
                    "Choose a shape color",
                    currentShapeColor
            );
            if (chosen != null) {
                currentShapeColor = chosen;
            }
        });
// Add pickColorButton to your toolbar or panel
        shapeToolBar.add(pickColorButton);
    }

    /**
     * Attempt to paste a shape using the current SelectionModel,
     * if it is a ProjectiveSelectionModel and the user has 4 corners selected.
     *
     * @param shapeType The shape to paste (e.g. "circle", "square", "oval", "triangle")
     * @param size      The size of the shape image in pixels (width and height)
     * @param color     The fill color of the shape
     */
    private void pasteShape(String shapeType, int size, Color color) {
        // 1) Check if our current selection is a ProjectiveSelectionModel
        if (!(selection() instanceof ProjectiveSelectionModel)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Please switch to a 'ProjectiveText' (4-corner) tool before pasting shapes.",
                    "Wrong tool",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        ProjectiveSelectionModel projModel = (ProjectiveSelectionModel) selection();

        // 2) Ensure the selection is finished
        if (projModel.state() != SelectionModel.SelectionState.SELECTED) {
            JOptionPane.showMessageDialog(
                    this,
                    "Please finish selecting exactly 4 corners first.",
                    "Incomplete Selection",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // 3) We can now paste the shape in perspective
        try {
            projModel.addPerspectiveShape(shapeType, size, color);
            repaint();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Error warping shape:\n" + ex.getMessage(),
                    "Shape Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }


    /**
     * Return the selection model we are currently controlling.
     */
    public SelectionModel selection() {
        return selector.getModel();
    }

    /**
     * Return the image we are currently displaying and selecting from.
     * Returns null if no image is currently set.
     */
    public BufferedImage image() {
        return selection().image();
    }

    /**
     * Have our selection interactions control `newModel` instead of our current model.
     * If we already have an image set, pass it to the new model.
     */
    public void setSelectionModel(SelectionModel newModel) {
        // If we currently have an image loaded, set it on the new model
        if (image() != null && !image().equals(newModel.image())) {
            newModel.setImage(image());
        }
        // Let the SelectionComponent use the new model
        selector.setModel(newModel);
    }

    /**
     * Display and select from `img`.
     * If `img` is null, show the placeholder.
     */
    public void setImage(BufferedImage img) {
        // Update or remove image in the selection model
        selection().setImage(img);

        if (img != null) {
            // Set the label's icon
            pic.setIcon(new ImageIcon(img));
            cardLayout.show(cardPanel, "image");
        } else {
            // Revert to placeholder
            pic.setIcon(null);
            cardLayout.show(cardPanel, "placeholder");
        }
        repaint();
    }
}

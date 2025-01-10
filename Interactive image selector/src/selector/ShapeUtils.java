package selector;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

public class ShapeUtils {

    /**
     * Creates a BufferedImage containing the specified shape.
     *
     * @param shapeType The type of shape to create ("circle", "square", "oval", "triangle").
     * @param size      The size of the image (width and height).
     * @param color     The color of the shape.
     * @return A BufferedImage with the drawn shape.
     */
    public static BufferedImage createShapeImage(String shapeType, int size, Color color) {
        BufferedImage shapeImg = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = shapeImg.createGraphics();

        // Enable anti-aliasing for smoother shapes
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Clear the image with a transparent background
        g2d.setComposite(AlphaComposite.Clear);
        g2d.fillRect(0, 0, size, size);
        g2d.setComposite(AlphaComposite.SrcOver);

        g2d.setColor(color);

        switch (shapeType.toLowerCase()) {
            case "circle":
                g2d.fillOval(0, 0, size, size);
                break;
            case "square":
                g2d.fillRect(0, 0, size, size);
                break;
            case "oval":
                g2d.fillOval(0, size / 4, size, size / 2);
                break;
            case "triangle":
                Polygon triangle = new Polygon();
                triangle.addPoint(size / 2, 0);
                triangle.addPoint(0, size);
                triangle.addPoint(size, size);
                g2d.fillPolygon(triangle);
                break;
            case "pentagon":
                Polygon pentagon = new Polygon();
                for (int i = 0; i < 5; i++) {
                    pentagon.addPoint(
                            (int) (size / 2 + size / 2 * Math.cos(i * 2 * Math.PI / 5 - Math.PI / 2)),
                            (int) (size / 2 + size / 2 * Math.sin(i * 2 * Math.PI / 5 - Math.PI / 2))
                    );
                }
                g2d.fillPolygon(pentagon);
                break;
            // Add more shapes as needed
            default:
                throw new IllegalArgumentException("Unsupported shape type: " + shapeType);
        }

        g2d.dispose();
        return shapeImg;
    }



}

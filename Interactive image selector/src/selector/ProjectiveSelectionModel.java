package selector;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

/**
 * A SelectionModel that specifically:
 *  - Expects exactly 4 user-clicked points (corners).
 *  - Once the 4th point is added, automatically closes the selection (SELECTED state).
 *  - Provides a method to add perspective text (homography warp).
 */
public class ProjectiveSelectionModel extends SelectionModel {
    /**
     * The original pasted image/text before any transformations.
     */
    private BufferedImage pastedContent;

    /**
     * Indicates whether the pasted content is text or an image.
     */
    private boolean isText;
    private BufferedImage originalImg;

    // Override the setImage method to create a deep copy
    @Override
    public void setImage(BufferedImage img) {
        super.setImage(img);
        if (img != null) {
            // Create a deep copy of the original image
            originalImg = new BufferedImage(
                    img.getColorModel(),
                    img.copyData(null),
                    img.isAlphaPremultiplied(),
                    null
            );
        } else {
            originalImg = null;
        }
    }

    private void resetImage() {
        if (originalImg == null) return; // Nothing to reset

        // Clear the current image by drawing the original image onto it
        Graphics2D g2d = img.createGraphics();
        g2d.setComposite(AlphaComposite.Src);
        g2d.drawImage(originalImg, 0, 0, null);
        g2d.dispose();
    }

    public ProjectiveSelectionModel(boolean notifyOnEdt) {
        super(notifyOnEdt);
        pastedContent = null;
        isText = false;
    }

    @Override
    public PolyLine liveWire(Point p) {
        // For a "projective text" tool, we can just connect lastPoint → p
        // in a straight line (like point-to-point).
        return new PolyLine(lastPoint(), p);
    }

    @Override
    protected void appendToSelection(Point p) {
        // Create a new line from the last point to 'p'
        PolyLine lineSeg = new PolyLine(lastPoint(), p);
        selection.add(lineSeg);

        // If we've reached 4 corners, auto-finish
        if (selection.size() == 4) {
            finishSelection();
        }
    }

    @Override
    public void movePoint(int index, Point newPos) {
        // This model might allow corner-dragging only after it's SELECTED
        if (state() != SelectionState.SELECTED) {
            throw new IllegalStateException("May not move corner in state " + state());
        }
        if (index < 0 || index >= selection.size()) {
            throw new IllegalArgumentException("Invalid corner index " + index);
        }

        PolyLine oldLine = selection.get(index);
        Point oldStart = oldLine.start();
        Point oldEnd = oldLine.end();


        PolyLine newLine = new PolyLine(newPos, oldEnd);
        selection.set(index, newLine);

        int prevIndex = (index - 1 + selection.size()) % selection.size();
        PolyLine prevLine = selection.get(prevIndex);
        selection.set(prevIndex, new PolyLine(prevLine.start(), newPos));

        if (pastedContent != null) {
            applyHomography();
        }

        propSupport.firePropertyChange("selection", null, selection());
    }

    /**
     * Adds perspective text by rendering it onto the image with a homography based on the selection.
     *
     * @param text  The text to render.
     * @param color The color of the text.
     */
    public void addPerspectiveText(String text, Color color) {
        if (state() != SelectionState.SELECTED) {
            throw new IllegalStateException("Must finish selection first");
        }

        // Render the text into a BufferedImage
        BufferedImage textImg = renderText(text, new Font("Serif", Font.BOLD, 36), color);
        pastedContent = textImg;
        isText = true;

        // Apply homography to warp and overlay the text
        applyHomography();
    }

    /**
     * Adds a perspective-warped image based on the current selection.
     *
     * @param pasteImg The image to paste.
     */
    public void addPerspectiveImage(BufferedImage pasteImg) {
        if (state() != SelectionState.SELECTED) {
            throw new IllegalStateException("Must finish selection first");
        }
        pastedContent = pasteImg;
        isText = false;

        // Apply homography to warp and overlay the image
        applyHomography();
    }


    // ============================================================
    // ============ HELPER METHODS FOR PERSPECTIVE TEXT ===========
    // ============================================================

    /**
     * Creates a small ARGB image with the given text.
     */
    private BufferedImage renderText(String text, Font font, Color color) {
        // measure text
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var g2 = tmp.createGraphics();
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(text);
        int h = fm.getHeight();
        g2.dispose();

        // create real image
        BufferedImage textImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        g2 = textImg.createGraphics();
        g2.setFont(font);
        g2.setColor(color);
        g2.drawString(text, 0, fm.getAscent());
        g2.dispose();

        return textImg;
    }

    /**
     * Compute a 3x3 homography matrix mapping:
     * src[0] -> dst[0], src[1] -> dst[1], src[2] -> dst[2], src[3] -> dst[3].
     *
     * Each src[i], dst[i] is a Point2D (double).
     * Returns double[3][3].
     */
    private double[][] computeHomography(Point2D[] src, Point2D[] dst) {
        if (src.length != 4 || dst.length != 4) {
            throw new IllegalArgumentException("Exactly four source and destination points are required.");
        }

        double[][] A = new double[8][8];
        double[] B = new double[8];

        for (int i = 0; i < 4; i++) {
            double x = src[i].getX();
            double y = src[i].getY();
            double X = dst[i].getX();
            double Y = dst[i].getY();

            A[2 * i][0] = x;
            A[2 * i][1] = y;
            A[2 * i][2] = 1;
            A[2 * i][3] = 0;
            A[2 * i][4] = 0;
            A[2 * i][5] = 0;
            A[2 * i][6] = -x * X;
            A[2 * i][7] = -y * X;
            B[2 * i] = X;

            A[2 * i + 1][0] = 0;
            A[2 * i + 1][1] = 0;
            A[2 * i + 1][2] = 0;
            A[2 * i + 1][3] = x;
            A[2 * i + 1][4] = y;
            A[2 * i + 1][5] = 1;
            A[2 * i + 1][6] = -x * Y;
            A[2 * i + 1][7] = -y * Y;
            B[2 * i + 1] = Y;
        }

        double[] sol = solveLinearSystem(A, B); // We'll define solveLinearSystem below

        // Our final matrix is [ [a b c], [d e f], [g h 1] ]
        double[][] H = new double[3][3];
        H[0][0] = sol[0]; // a
        H[0][1] = sol[1]; // b
        H[0][2] = sol[2]; // c
        H[1][0] = sol[3]; // d
        H[1][1] = sol[4]; // e
        H[1][2] = sol[5]; // f
        H[2][0] = sol[6]; // g
        H[2][1] = sol[7]; // h
        H[2][2] = 1.0;

        return H;
    }

    /**
     * Applies the homography to the pasted content and overlays it onto the main image.
     */
    private void applyHomography() {
        if (pastedContent == null || originalImg == null) return;

        // Define source points (corners of the pasted content)
        Point2D[] src = new Point2D[4];
        src[0] = new Point2D.Double(0, 0);
        src[1] = new Point2D.Double(pastedContent.getWidth(), 0);
        src[2] = new Point2D.Double(pastedContent.getWidth(), pastedContent.getHeight());
        src[3] = new Point2D.Double(0, pastedContent.getHeight());

        // Define destination points (selected corners)
        Point2D[] dst = new Point2D[4];
        Polygon poly = PolyLine.makePolygon(selection);
        for (int i = 0; i < 4; i++) {
            dst[i] = new Point2D.Double(poly.xpoints[i], poly.ypoints[i]);
        }

        // Compute homography matrix
        double[][] H = computeHomography(src, dst);

        // Reset 'img' to the original image
        resetImage();

        // Perform warping and overlay in a background thread to keep UI responsive
        new Thread(() -> {
            warpAndOverlay(pastedContent, img, H);

            // Repaint the image panel on the EDT
            SwingUtilities.invokeLater(() -> {
                propSupport.firePropertyChange("image", null, img);
            });
        }).start();
    }



    /**
     * Solve an 8x8 linear system using Gaussian elimination.
     * A is 8x8, B is length 8.
     * Return the solution array of length 8.
     */
    private double[] solveLinearSystem(double[][] A, double[] B) {
        int n = 8;
        // Create augmented matrix
        double[][] augmented = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, augmented[i], 0, n);
            augmented[i][n] = B[i];
        }

        // Perform Gaussian elimination
        for (int i = 0; i < n; i++) {
            // Find pivot for column i
            int max = i;
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(augmented[k][i]) > Math.abs(augmented[max][i])) {
                    max = k;
                }
            }

            // Swap rows if needed
            double[] temp = augmented[i];
            augmented[i] = augmented[max];
            augmented[max] = temp;

            // Check for singular matrix
            if (Math.abs(augmented[i][i]) < 1e-10) {
                throw new IllegalArgumentException("Singular matrix - cannot compute homography.");
            }

            // Eliminate below
            for (int k = i + 1; k < n; k++) {
                double factor = augmented[k][i] / augmented[i][i];
                for (int j = i; j <= n; j++) {
                    augmented[k][j] -= factor * augmented[i][j];
                }
            }
        }

        // Back substitution
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            x[i] = augmented[i][n];
            for (int j = i + 1; j < n; j++) {
                x[i] -= augmented[i][j] * x[j];
            }
            x[i] /= augmented[i][i];
        }

        return x;
    }



//    /**
//     * Warp each pixel in `srcImg` into `destImg` using matrix H.
//     * If you want alpha blending, you'd add logic.
//     */
//    private void warpImage(BufferedImage srcImg, BufferedImage destImg, double[][] H) {
//        double a = H[0][0], b = H[0][1], c = H[0][2];
//        double d = H[1][0], e = H[1][1], f = H[1][2];
//        double g = H[2][0], h = H[2][1]; // H[2][2] = 1
//
//        for (int v = 0; v < srcImg.getHeight(); v++) {
//            for (int u = 0; u < srcImg.getWidth(); u++) {
//                int srcARGB = srcImg.getRGB(u, v);
//
//                // If the source pixel is fully transparent, skip
//                if (((srcARGB >> 24) & 0xFF) == 0) {
//                    continue;
//                }
//
//                double X = a*u + b*v + c;
//                double Y = d*u + e*v + f;
//                double W = g*u + h*v + 1.0;
//
//                int Xdst = (int) Math.round(X / W);
//                int Ydst = (int) Math.round(Y / W);
//
//                if (Xdst >= 0 && Xdst < destImg.getWidth() &&
//                        Ydst >= 0 && Ydst < destImg.getHeight()) {
//
//                    // Get the existing color at (Xdst, Ydst)
//                    int dstARGB = destImg.getRGB(Xdst, Ydst);
//
//                    // Blend them
//                    int blendedARGB = blend(srcARGB, dstARGB);
//
//                    // Write the blended color back
//                    destImg.setRGB(Xdst, Ydst, blendedARGB);
//                }
//            }
//        }
//    }

    /**
     * Warps the source image using the homography matrix and overlays it onto the destination image.
     *
     * @param srcImg  The source image to warp.
     * @param destImg The destination image to overlay onto.
     * @param H       The homography matrix.
     */
    private void warpAndOverlay(BufferedImage srcImg, BufferedImage destImg, double[][] H) {
        // Compute the inverse homography matrix
        double[][] Hinv = invertHomography(H);
        if (Hinv == null) {
            throw new IllegalArgumentException("Homography matrix is singular and cannot be inverted.");
        }

        int destWidth = destImg.getWidth();
        int destHeight = destImg.getHeight();

        for (int y = 0; y < destHeight; y++) {
            for (int x = 0; x < destWidth; x++) {
                // Apply inverse homography to destination pixel
                double[] srcPt = applyHomography(Hinv, x, y);

                double srcX = srcPt[0];
                double srcY = srcPt[1];

                // Perform bilinear interpolation
                int rgb = bilinearInterpolate(srcImg, srcX, srcY);

                // Blend the source pixel with the destination pixel
                if (rgb != 0) { // Assuming transparent pixels have RGB=0
                    int destRGB = destImg.getRGB(x, y);
                    int blendedRGB = blend(rgb, destRGB);
                    destImg.setRGB(x, y, blendedRGB);
                }
            }
        }

        // Notify listeners that the image has changed
        propSupport.firePropertyChange("image", null, destImg);
    }

    /**
     * Applies the homography matrix to a point (x, y).
     *
     * @param H The homography matrix.
     * @param x The x-coordinate.
     * @param y The y-coordinate.
     * @return The transformed point as a double array [x', y'].
     */
    private double[] applyHomography(double[][] H, double x, double y) {
        double denominator = H[2][0] * x + H[2][1] * y + H[2][2];
        double xPrime = (H[0][0] * x + H[0][1] * y + H[0][2]) / denominator;
        double yPrime = (H[1][0] * x + H[1][1] * y + H[1][2]) / denominator;
        return new double[]{xPrime, yPrime};
    }

    /**
     * Performs bilinear interpolation for non-integer pixel locations.
     *
     * @param img The source image.
     * @param x   The x-coordinate (can be non-integer).
     * @param y   The y-coordinate (can be non-integer).
     * @return The interpolated RGB value, or 0 if out of bounds.
     */
    private int bilinearInterpolate(BufferedImage img, double x, double y) {
        int x1 = (int) Math.floor(x);
        int y1 = (int) Math.floor(y);
        int x2 = x1 + 1;
        int y2 = y1 + 1;

        if (x1 < 0 || y1 < 0 || x2 >= img.getWidth() || y2 >= img.getHeight()) {
            return 0; // Transparent if out of bounds
        }

        double a = x - x1;
        double b = y - y1;

        int rgb11 = img.getRGB(x1, y1);
        int rgb21 = img.getRGB(x2, y1);
        int rgb12 = img.getRGB(x1, y2);
        int rgb22 = img.getRGB(x2, y2);

        int r = (int) (
                ((1 - a) * (1 - b) * ((rgb11 >> 16) & 0xFF)) +
                        (a * (1 - b) * ((rgb21 >> 16) & 0xFF)) +
                        ((1 - a) * b * ((rgb12 >> 16) & 0xFF)) +
                        (a * b * ((rgb22 >> 16) & 0xFF))
        );

        int g = (int) (
                ((1 - a) * (1 - b) * ((rgb11 >> 8) & 0xFF)) +
                        (a * (1 - b) * ((rgb21 >> 8) & 0xFF)) +
                        ((1 - a) * b * ((rgb12 >> 8) & 0xFF)) +
                        (a * b * ((rgb22 >> 8) & 0xFF))
        );

        int bl = (int) (
                ((1 - a) * (1 - b) * (rgb11 & 0xFF)) +
                        (a * (1 - b) * (rgb21 & 0xFF)) +
                        ((1 - a) * b * (rgb12 & 0xFF)) +
                        (a * b * (rgb22 & 0xFF))
        );

        int aAlpha = (rgb11 >> 24) & 0xFF;
        int a2Alpha = (rgb21 >> 24) & 0xFF;
        int a3Alpha = (rgb12 >> 24) & 0xFF;
        int a4Alpha = (rgb22 >> 24) & 0xFF;

        double alpha = ((1 - a) * (1 - b) * aAlpha +
                a * (1 - b) * a2Alpha +
                (1 - a) * b * a3Alpha +
                a * b * a4Alpha) / 255.0;

        // If alpha is 0, return transparent
        if (alpha == 0) return 0;

        // Return the blended color
        return (Math.min((int) (alpha * 255), 255) << 24) |
                (Math.min(r, 255) << 16) |
                (Math.min(g, 255) << 8) |
                Math.min(bl, 255);
    }

    /**
     * Inverts a 3x3 homography matrix.
     *
     * @param H The homography matrix.
     * @return The inverse homography matrix, or null if singular.
     */
    private double[][] invertHomography(double[][] H) {
        double a = H[0][0], b = H[0][1], c = H[0][2];
        double d = H[1][0], e = H[1][1], f = H[1][2];
        double g = H[2][0], h = H[2][1], i = H[2][2];

        double A = e * i - f * h;
        double B = -(d * i - f * g);
        double C = d * h - e * g;
        double D = -(b * i - c * h);
        double E = a * i - c * g;
        double F = -(a * h - b * g);
        double G = b * f - c * e;
        double HinvVal = -(a * f - c * d);
        double I = a * e - b * d;

        double det = a * A + b * B + c * C;

        if (Math.abs(det) < 1e-10) {
            return null; // Singular matrix
        }

        double[][] Hinv = {
                {A / det, D / det, G / det},
                {B / det, E / det, HinvVal / det},
                {C / det, F / det, I / det}
        };

        return Hinv;
    }


    /**
     * Blends a source ARGB pixel with a destination ARGB pixel using standard alpha compositing (SRC over DST).
     *
     * @param srcARGB The ARGB color of the source pixel.
     * @param dstARGB The ARGB color of the destination pixel.
     * @return The blended ARGB result.
     */
    private int blend(int srcARGB, int dstARGB) {
        // Extract source color components
        int srcA = (srcARGB >> 24) & 0xFF;
        int srcR = (srcARGB >> 16) & 0xFF;
        int srcG = (srcARGB >> 8) & 0xFF;
        int srcB = srcARGB & 0xFF;

        // Extract destination color components
        int dstA = (dstARGB >> 24) & 0xFF;
        int dstR = (dstARGB >> 16) & 0xFF;
        int dstG = (dstARGB >> 8) & 0xFF;
        int dstB = dstARGB & 0xFF;

        // Convert alpha from [0..255] to [0..1]
        float alphaSrc = srcA / 255f;
        float alphaDst = dstA / 255f;

        // Final alpha
        float outA = alphaSrc + alphaDst * (1 - alphaSrc);
        if (outA <= 0f) {
            // Fully transparent result
            return 0x00000000;
        }

        // Blend channels
        float outR = (srcR * alphaSrc + dstR * alphaDst * (1 - alphaSrc)) / outA;
        float outG = (srcG * alphaSrc + dstG * alphaDst * (1 - alphaSrc)) / outA;
        float outB = (srcB * alphaSrc + dstB * alphaDst * (1 - alphaSrc)) / outA;

        // Convert back to [0..255]
        int A = Math.round(outA * 255);
        int R = Math.round(outR);
        int G = Math.round(outG);
        int B = Math.round(outB);

        return (A << 24) | (R << 16) | (G << 8) | B;
    }

    // ================ END HELPER METHODS ================

    @Override
    protected void startSelection(Point start) {
        // We override because we specifically want to start and go to SELECTING
        if (state() != SelectionState.NO_SELECTION) {
            throw new IllegalStateException("Cannot start selection from state " + state());
        }
        this.start = new Point(start);
        setState(SelectionState.SELECTING);
    }

    /**
     * Override the finishSelection method to close the selection without adding a duplicate point.
     */
    @Override
    public void finishSelection() {
        if (state() != SelectionState.SELECTING) {
            throw new IllegalStateException("Cannot finish selection in state " + state());
        }

        if (selection.size() < 4) {
            // Handle incomplete selection as per your design choice
            if (selection.isEmpty()) {
                reset();
                return;
            }
            // Optionally, you can allow closing with fewer points
        }

        // Create a closing segment from the last point to the start point
        PolyLine closingLine = new PolyLine(lastPoint(), start);
        selection.add(closingLine);

        // Update the state to SELECTED
        setState(SelectionState.SELECTED);
    }
}
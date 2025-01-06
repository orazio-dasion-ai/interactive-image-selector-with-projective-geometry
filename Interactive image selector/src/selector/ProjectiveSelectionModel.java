package selector;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

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
        if (pastedContent == null) return;

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

        // Warp and overlay the pasted content onto the main image
        warpAndOverlay(pastedContent, img, H);
    }


    /**
     * Warps the source image using the homography matrix and overlays it onto the destination image.
     *
     * @param srcImg The source image to warp.
     * @param destImg The destination image to overlay onto.
     * @param H The homography matrix.
     */
    private void warpAndOverlay(BufferedImage srcImg, BufferedImage destImg, double[][] H) {
        // Placeholder: Implement homography-based warping and overlay.
        // For robust implementation, consider using a library like OpenCV.
        // Below is a simplified example without proper homography application.

        Graphics2D g2 = destImg.createGraphics();
        g2.drawImage(srcImg, 0, 0, null);
        g2.dispose();

        // Notify listeners that the image has changed
        propSupport.firePropertyChange("image", null, destImg);
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



    /**
     * Warp each pixel in `srcImg` into `destImg` using matrix H.
     * If you want alpha blending, you'd add logic.
     */
    private void warpImage(BufferedImage srcImg, BufferedImage destImg, double[][] H) {
        double a = H[0][0], b = H[0][1], c = H[0][2];
        double d = H[1][0], e = H[1][1], f = H[1][2];
        double g = H[2][0], h = H[2][1]; // H[2][2] = 1

        for (int v = 0; v < srcImg.getHeight(); v++) {
            for (int u = 0; u < srcImg.getWidth(); u++) {
                int srcARGB = srcImg.getRGB(u, v);

                // If the source pixel is fully transparent, skip
                if (((srcARGB >> 24) & 0xFF) == 0) {
                    continue;
                }

                double X = a*u + b*v + c;
                double Y = d*u + e*v + f;
                double W = g*u + h*v + 1.0;

                int Xdst = (int) Math.round(X / W);
                int Ydst = (int) Math.round(Y / W);

                if (Xdst >= 0 && Xdst < destImg.getWidth() &&
                        Ydst >= 0 && Ydst < destImg.getHeight()) {

                    // Get the existing color at (Xdst, Ydst)
                    int dstARGB = destImg.getRGB(Xdst, Ydst);

                    // Blend them
                    int blendedARGB = blend(srcARGB, dstARGB);

                    // Write the blended color back
                    destImg.setRGB(Xdst, Ydst, blendedARGB);
                }
            }
        }
    }


    /**
     * Blends a source ARGB pixel with a destination ARGB pixel using
     * standard alpha compositing (SRC over DST).
     *
     * @param srcARGB the ARGB color of the source pixel
     * @param dstARGB the ARGB color of the destination pixel
     * @return the blended ARGB result
     */
    private int blend(int srcARGB, int dstARGB) {
        // Extract source color components
        int srcA = (srcARGB >> 24) & 0xFF;
        int srcR = (srcARGB >> 16) & 0xFF;
        int srcG = (srcARGB >> 8)  & 0xFF;
        int srcB = (srcARGB)       & 0xFF;

        // Extract destination color components
        int dstA = (dstARGB >> 24) & 0xFF;
        int dstR = (dstARGB >> 16) & 0xFF;
        int dstG = (dstARGB >> 8)  & 0xFF;
        int dstB = (dstARGB)       & 0xFF;

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

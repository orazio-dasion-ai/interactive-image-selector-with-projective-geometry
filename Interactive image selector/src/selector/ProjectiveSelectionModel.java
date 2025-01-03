package selector;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
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

    public ProjectiveSelectionModel(boolean notifyOnEdt) {
        super(notifyOnEdt);
    }

    // If you wanted to copy from an existing model, e.g., the current selection,
    // you can create a constructor that does:
    // public ProjectiveTextSelectionModel(SelectionModel copy) { super(copy); }

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

        propSupport.firePropertyChange("selection", null, selection());
    }

    /**
     * Render text into this model's image using a projective warp from a 4-corner region
     * in the selection to the text's rectangle.
     *
     * For simplicity, we show a direct pixel-by-pixel approach (homography).
     */
    public void addPerspectiveText(String text, Color color) {
        if (state() != SelectionState.SELECTED) {
            throw new IllegalStateException("Must finish selection first");
        }

        Polygon poly = PolyLine.makePolygon(selection);
//        if (poly.npoints != 4) {
//            throw new IllegalArgumentException("This tool requires exactly 4 corners.");
//        }

        // 1) Render the text in a small ARGB image
        BufferedImage textImg = renderText(text, new Font("Serif", Font.BOLD, 36), color);

        // 2) Extract the 4 corners from the polygon
        Point2D src0 = new Point2D.Double(0, 0);
        Point2D src1 = new Point2D.Double(textImg.getWidth(), 0);
        Point2D src2 = new Point2D.Double(textImg.getWidth(), textImg.getHeight());
        Point2D src3 = new Point2D.Double(0, textImg.getHeight());

        Point2D dst0 = new Point2D.Double(poly.xpoints[0], poly.ypoints[0]);
        Point2D dst1 = new Point2D.Double(poly.xpoints[1], poly.ypoints[1]);
        Point2D dst2 = new Point2D.Double(poly.xpoints[2], poly.ypoints[2]);
        Point2D dst3 = new Point2D.Double(poly.xpoints[3], poly.ypoints[3]);

        double[][] H = computeHomography(src0, src1, src2, src3, dst0, dst1, dst2, dst3);

        // 3) Warp textImg onto img
        warpImage(textImg, img, H);
    }

    public void addPerspectiveImage(BufferedImage pasteImg) {
        // Must be SELECTED with exactly 4 corners
        if (state() != SelectionState.SELECTED) {
            throw new IllegalStateException("Must finish selection first");
        }
        Polygon poly = PolyLine.makePolygon(selection);
//        if (poly.npoints != 4) {
//            throw new IllegalArgumentException("This tool requires exactly 4 corners.");
//        }

        // 1) We'll define the "source" corners from (0,0) to (width,height)
        int w = pasteImg.getWidth();
        int h = pasteImg.getHeight();

        Point2D src0 = new Point2D.Double(0, 0);
        Point2D src1 = new Point2D.Double(w, 0);
        Point2D src2 = new Point2D.Double(w, h);
        Point2D src3 = new Point2D.Double(0, h);

        // 2) Destination corners from the polygon
        Point2D dst0 = new Point2D.Double(poly.xpoints[0], poly.ypoints[0]);
        Point2D dst1 = new Point2D.Double(poly.xpoints[1], poly.ypoints[1]);
        Point2D dst2 = new Point2D.Double(poly.xpoints[2], poly.ypoints[2]);
        Point2D dst3 = new Point2D.Double(poly.xpoints[3], poly.ypoints[3]);

        // 3) Compute the 3x3 homography
        double[][] H = computeHomography(src0, src1, src2, src3,
                dst0, dst1, dst2, dst3);

        // 4) Warp
        warpImage(pasteImg, img, H);
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
     * Compute a 3x3 homography mapping:
     * src0 -> dst0, src1 -> dst1, src2 -> dst2, src3 -> dst3.
     *
     * Each srcN, dstN is a Point2D (double).
     * Returns double[3][3].
     */
    private double[][] computeHomography(Point2D src0, Point2D src1, Point2D src2, Point2D src3,
            Point2D dst0, Point2D dst1, Point2D dst2, Point2D dst3) {
        // Convert them into arrays for convenience
        double[][] src = {
                { src0.getX(), src0.getY() },
                { src1.getX(), src1.getY() },
                { src2.getX(), src2.getY() },
                { src3.getX(), src3.getY() }
        };
        double[][] dst = {
                { dst0.getX(), dst0.getY() },
                { dst1.getX(), dst1.getY() },
                { dst2.getX(), dst2.getY() },
                { dst3.getX(), dst3.getY() }
        };

        // We'll build an 8x8 matrix and 8x1 vector for the equations
        // Then solve for h = [a b c d e f g h].
        // The final matrix is [ [a b c], [d e f], [g h 1] ].

        double[][] A = new double[8][8];
        double[] B = new double[8];

        for (int i = 0; i < 4; i++) {
            double x = src[i][0];
            double y = src[i][1];
            double X = dst[i][0];
            double Y = dst[i][1];

            // Equation for the i-th pair:
            // X = a*x + b*y + c
            // Y = d*x + e*y + f
            // plus the perspective terms for g,h:
            // X = (a*x + b*y + c) / (g*x + h*y + 1)
            // but we rearrange to get linear forms. The standard approach is:
            //   x' = a*x + b*y + c - g*x*x' - h*y*x'
            //   y' = d*x + e*y + f - g*x*y' - h*y*y'
            // We'll treat each pair as two rows in A.

            A[2*i][0] = x;
            A[2*i][1] = y;
            A[2*i][2] = 1;
            A[2*i][3] = 0;
            A[2*i][4] = 0;
            A[2*i][5] = 0;
            A[2*i][6] = -x * X;
            A[2*i][7] = -y * X;
            B[2*i]   = X;

            A[2*i + 1][0] = 0;
            A[2*i + 1][1] = 0;
            A[2*i + 1][2] = 0;
            A[2*i + 1][3] = x;
            A[2*i + 1][4] = y;
            A[2*i + 1][5] = 1;
            A[2*i + 1][6] = -x * Y;
            A[2*i + 1][7] = -y * Y;
            B[2*i + 1] = Y;
        }

        // Solve A*h = B for h. We can use a simple Gaussian elimination or a library like Apache Commons Math
        double[] sol = solveLinearSystem(A, B);  // We'll define solveLinearSystem below

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
     * Solve an 8x8 linear system using naive Gaussian elimination.
     * A is 8x8, B is length 8.
     * Return the solution array of length 8.
     */
    private double[] solveLinearSystem(double[][] A, double[] B) {
        int n = 8;
        // We can do a basic elimination
        for (int i = 0; i < n; i++) {
            // find pivot
            int pivot = i;
            double max = Math.abs(A[i][i]);
            for (int r = i+1; r < n; r++) {
                double val = Math.abs(A[r][i]);
                if (val > max) {
                    max = val;
                    pivot = r;
                }
            }
            // swap if pivot not i
            if (pivot != i) {
                double[] tempRow = A[i];
                A[i] = A[pivot];
                A[pivot] = tempRow;

                double tempB = B[i];
                B[i] = B[pivot];
                B[pivot] = tempB;
            }
            // eliminate below
            for (int r = i+1; r < n; r++) {
                double factor = A[r][i] / A[i][i];
                for (int c = i; c < n; c++) {
                    A[r][c] -= factor * A[i][c];
                }
                B[r] -= factor * B[i];
            }
        }
        // back-substitution
        double[] x = new double[n];
        for (int i = n-1; i >= 0; i--) {
            double sum = B[i];
            for (int c = i+1; c < n; c++) {
                sum -= A[i][c] * x[c];
            }
            x[i] = sum / A[i][i];
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

    @Override
    public void finishSelection() {
        // If we have fewer than 4 points, consider it incomplete
        // or you can auto-close the shape. Your choice:
        if (selection.size() < 4) {
            // you could forcibly connect the last corner to the first
            // or just reset, depending on your design:
            // For demonstration, let's forcibly close with however many corners we have:
            if (selection.isEmpty()) {
                reset();
                return;
            }
        }
        // final step: connect last corner to the first corner
        addPoint(start);
        setState(SelectionState.SELECTED);
    }
}

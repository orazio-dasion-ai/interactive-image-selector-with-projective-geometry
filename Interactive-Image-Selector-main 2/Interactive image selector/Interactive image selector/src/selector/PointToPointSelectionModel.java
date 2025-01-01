package selector;

import java.awt.Point;
import java.util.ListIterator;

/**
 * Models a selection tool that connects each added point with a straight line.
 */
public class PointToPointSelectionModel extends SelectionModel {

    public PointToPointSelectionModel(boolean notifyOnEdt) {
        super(notifyOnEdt);
    }

    public PointToPointSelectionModel(SelectionModel copy) {
        super(copy);
    }

    /**
     * Return a straight line segment from our last point to `p`.
     */
    @Override
    public PolyLine liveWire(Point p) {
        //  Test immediately with `testLiveWireEmpty()`, and think about how the test might change
        //  for non-empty selections (see task 2D).
        PolyLine polyLine = new PolyLine(lastPoint(), p);

        return polyLine;
    }

    /**
     * Append a straight line segment to the current selection path connecting its end with `p`.
     */
    @Override
    protected void appendToSelection(Point p) {

        //  Test immediately with `testAppend()` and `testFinishSelection()`.
        PolyLine lineSeg = new PolyLine(lastPoint(), p);
        selection.add(lineSeg);
    }

    /**
     * Move the starting point of the segment of our selection with index `index` to `newPos`,
     * connecting to the end of that segment with a straight line and also connecting `newPos` to
     * the start of the previous segment (wrapping around) with a straight line (these straight
     * lines replace both previous segments).  Notify listeners that the "selection" property has
     * changed.
     */
    @Override
    public void movePoint(int index, Point newPos) {
        // Confirm that we have a closed selection and that `index` is valid
        if (state() != SelectionState.SELECTED) {
            throw new IllegalStateException("May not move point in state " + state());
        }
        if (index < 0 || index >= selection.size()) {
            throw new IllegalArgumentException("Invalid segment index " + index);
        }

        ListIterator<PolyLine> listIterator = selection.listIterator(index+1);

        PolyLine line1 = listIterator.previous();
        PolyLine line2 = new PolyLine(newPos, line1.end());

        listIterator.set(line2);

        if (listIterator.hasPrevious()) {
            PolyLine newPrevLine = new PolyLine (listIterator.previous().start(), newPos);
            listIterator.set(newPrevLine);
        } else {
            listIterator = selection.listIterator(selection.size());
            PolyLine line3 = new PolyLine (listIterator.previous().start(), newPos);

            listIterator.set(line3);
            start = newPos;
        }
        propSupport.firePropertyChange("selection", null, selection());
    }
}
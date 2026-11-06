package nurgling.survey;

import haven.res.ui.surv.Data;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded undo/redo stack for one LandSurvey editor instance (owned by that window, not global
 * state shared across characters/sessions). One Edit per completed gesture: a single drag
 * (Mover/CMover), or a single Ground Level/Ground Plane click.
 */
public class SurveyEditHistory {
    private static final class Edit {
        final int[] idx;
        final float[] before, after;
        Edit(int[] idx, float[] before, float[] after) {
            this.idx = idx;
            this.before = before;
            this.after = after;
        }
    }

    private final Deque<Edit> undo = new ArrayDeque<>();
    private final Deque<Edit> redo = new ArrayDeque<>();

    private int cap() {
        return SurveySettings.undoDepth();
    }

    private static boolean changed(int[] idx, float[] before, float[] after) {
        for(int i = 0; i < idx.length; i++) {
            if(before[i] != after[i])
                return true;
        }
        return false;
    }

    /** Pushes one committed edit. No-op (nothing recorded) if before == after. */
    public void push(int[] idx, float[] before, float[] after) {
        if(!changed(idx, before, after))
            return;
        undo.push(new Edit(idx.clone(), before.clone(), after.clone()));
        while(undo.size() > cap())
            undo.removeLast();
        redo.clear();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    /** Applies the most recent edit's "before" state to data.wz; caller must dupdate()/send(). */
    public boolean undo(Data data) {
        Edit e = undo.poll();
        if(e == null)
            return false;
        for(int i = 0; i < e.idx.length; i++)
            data.wz[e.idx[i]] = e.before[i];
        redo.push(e);
        return true;
    }

    /** Applies the most recently undone edit's "after" state to data.wz. */
    public boolean redo(Data data) {
        Edit e = redo.poll();
        if(e == null)
            return false;
        for(int i = 0; i < e.idx.length; i++)
            data.wz[e.idx[i]] = e.after[i];
        undo.push(e);
        return true;
    }

    /**
     * Called whenever the server sends survey data this client didn't just author itself
     * (i.e. the decoded array doesn't match what our own last edit produced). Dropping the whole
     * history is the only safe response — replaying local undo/redo entries over someone else's
     * changes would silently clobber them.
     */
    public void onExternalData(float[] preDecodeWz, float[] postDecodeWz) {
        if(undo.isEmpty() && redo.isEmpty())
            return;
        for(int i = 0; i < preDecodeWz.length; i++) {
            if(preDecodeWz[i] != postDecodeWz[i]) {
                clear();
                return;
            }
        }
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}

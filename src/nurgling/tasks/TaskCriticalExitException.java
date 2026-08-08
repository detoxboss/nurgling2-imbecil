package nurgling.tasks;

/**
 * Thrown by NCore.addTask() when a bounded NTask hits its own timeout (criticalExit) - distinct
 * from a genuine thread interruption (stop button / bot cancel), which throws a plain
 * InterruptedException instead. This is a subclass of InterruptedException so every existing
 * `catch (InterruptedException e)` call site keeps working unchanged; a caller that needs to tell
 * the two apart must catch this subtype first (before a broader InterruptedException catch) and
 * let a plain InterruptedException propagate untouched.
 *
 * Distinguishing the two via Thread.currentThread().isInterrupted() after the catch does NOT
 * work: Object.wait() clears the thread's interrupt flag when it throws InterruptedException for
 * a real interruption, so both cases read as "not interrupted" once caught - confirmed live
 * 2026-08 as the reason a genuine stop-button interruption could be silently swallowed as a
 * recoverable task timeout by code using that check (LpAssistantBot, and the same pattern in
 * SortContainersInArea.tryTransfer() - not fixed there, out of scope for this change, but it has
 * the identical latent bug).
 */
public class TaskCriticalExitException extends InterruptedException {
    public final NTask task;

    public TaskCriticalExitException(NTask task) {
        super("Incorrect final of task " + task.getClass().toString());
        this.task = task;
    }
}

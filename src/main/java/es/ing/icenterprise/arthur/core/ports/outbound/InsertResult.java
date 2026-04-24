package es.ing.icenterprise.arthur.core.ports.outbound;

/**
 * Outcome of a batch insert attempt. When a chunk contains one or more bad
 * rows, the adapter bisects the chunk and retries, so good rows still get
 * persisted while bad rows are counted and logged.
 *
 * @param inserted rows successfully persisted
 * @param failed   rows rejected by the database (constraint violations, bad
 *                 values, etc.) after the bisection isolated them
 */
public record InsertResult(int inserted, int failed) {

    public static final InsertResult EMPTY = new InsertResult(0, 0);

    public InsertResult plus(InsertResult other) {
        return new InsertResult(this.inserted + other.inserted, this.failed + other.failed);
    }
}

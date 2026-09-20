package com.schwab.shortener.orchestrator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dependency graph the orchestrator executes.
 *
 * <p>This is a DAG, not a pipeline, and the difference is load-bearing. IMPLEMENT and
 * DOCS have the same single predecessor and no edge between them, so they run
 * concurrently. RELEASE_APPROVAL depends on both TEST and DOCS, so it is a
 * synchronization point that cannot start until two independent branches have both
 * finished - and it is also the second human gate, because releasing is the most
 * high-impact action in the run.
 *
 * <pre>
 *   REQUIREMENTS
 *        |
 *     DESIGN
 *        |
 *    APPROVAL            &lt;- human gate 1: approve the plan
 *      /     \
 *  IMPLEMENT  DOCS       &lt;- parallel
 *      |        |
 *    TEST       |
 *      \       /
 *  RELEASE_APPROVAL      &lt;- join + human gate 2: approve the release
 *         |
 *      RELEASE
 * </pre>
 *
 * <p>Entry gate for every stage: all dependencies satisfied, then {@link PolicyGuard}.
 * Exit gate for every stage: {@link StageExitCriteria} on what the agent produced.
 */
public final class StageGraph {

    private static final Map<Stage, Set<Stage>> DEPENDENCIES = new EnumMap<>(Stage.class);

    static {
        DEPENDENCIES.put(Stage.REQUIREMENTS, EnumSet.noneOf(Stage.class));
        DEPENDENCIES.put(Stage.DESIGN, EnumSet.of(Stage.REQUIREMENTS));
        DEPENDENCIES.put(Stage.APPROVAL, EnumSet.of(Stage.DESIGN));
        DEPENDENCIES.put(Stage.IMPLEMENT, EnumSet.of(Stage.APPROVAL));
        DEPENDENCIES.put(Stage.DOCS, EnumSet.of(Stage.APPROVAL));
        DEPENDENCIES.put(Stage.TEST, EnumSet.of(Stage.IMPLEMENT));
        DEPENDENCIES.put(Stage.RELEASE_APPROVAL, EnumSet.of(Stage.TEST, Stage.DOCS));
        DEPENDENCIES.put(Stage.RELEASE, EnumSet.of(Stage.RELEASE_APPROVAL));
    }

    /** Stages that require a human decision before anything downstream may run. */
    private static final Set<Stage> GATES = EnumSet.of(Stage.APPROVAL, Stage.RELEASE_APPROVAL);

    /**
     * Stages whose effects must be undone if the run is compensated. DOCS and the
     * read-only analysis stages leave nothing behind that needs reversing.
     */
    private static final Set<Stage> COMPENSATABLE = EnumSet.of(Stage.IMPLEMENT, Stage.RELEASE);

    private StageGraph() {
    }

    public static List<Stage> listStages() {
        return List.of(Stage.values());
    }

    public static Set<Stage> getDependencies(Stage stage) {
        return Collections.unmodifiableSet(DEPENDENCIES.getOrDefault(stage, EnumSet.noneOf(Stage.class)));
    }

    /**
     * Every stage that depends on {@code stage}, directly or through other stages.
     * When a stage's output changes, all of these are working from stale input and
     * have to be re-planned.
     */
    public static Set<Stage> getDownstream(Stage stage) {
        Set<Stage> found = EnumSet.noneOf(Stage.class);
        Deque<Stage> toVisit = new ArrayDeque<>();
        toVisit.add(stage);
        while (!toVisit.isEmpty()) {
            Stage current = toVisit.poll();
            for (Stage candidate : Stage.values()) {
                if (getDependencies(candidate).contains(current) && found.add(candidate)) {
                    toVisit.add(candidate);
                }
            }
        }
        return found;
    }

    public static boolean isGate(Stage stage) {
        return GATES.contains(stage);
    }

    public static boolean isCompensatable(Stage stage) {
        return COMPENSATABLE.contains(stage);
    }

    /** Guards against a malformed graph at startup rather than at 3am. */
    public static void validateNoCycles() {
        Set<Stage> settled = EnumSet.noneOf(Stage.class);
        boolean progressed = true;
        while (progressed && settled.size() < Stage.values().length) {
            progressed = false;
            for (Stage stage : Stage.values()) {
                if (settled.contains(stage)) {
                    continue;
                }
                if (settled.containsAll(getDependencies(stage))) {
                    settled.add(stage);
                    progressed = true;
                }
            }
        }
        if (settled.size() != Stage.values().length) {
            throw new IllegalStateException("stage graph contains a cycle");
        }
    }
}

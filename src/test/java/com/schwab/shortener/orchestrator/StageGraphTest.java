package com.schwab.shortener.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageGraphTest {

    @Test
    @DisplayName("the graph has no cycles, so every run can finish")
    void graphHasNoCycles() {
        assertDoesNotThrow(StageGraph::validateNoCycles);
    }

    @Test
    @DisplayName("the first stage waits for nothing")
    void firstStageWaitsForNothing() {
        assertTrue(StageGraph.getDependencies(Stage.REQUIREMENTS).isEmpty());
    }

    @Test
    @DisplayName("the plan and the release are both human gates")
    void planAndReleaseAreHumanGates() {
        assertTrue(StageGraph.isGate(Stage.APPROVAL));
        assertTrue(StageGraph.isGate(Stage.RELEASE_APPROVAL));
        assertFalse(StageGraph.isGate(Stage.IMPLEMENT));
    }

    /** This is what makes the graph a graph rather than a list. */
    @Test
    @DisplayName("implement and docs can run at the same time")
    void implementAndDocsCanRunTogether() {
        assertEquals(StageGraph.getDependencies(Stage.IMPLEMENT),
                     StageGraph.getDependencies(Stage.DOCS));
        assertFalse(StageGraph.getDependencies(Stage.IMPLEMENT).contains(Stage.DOCS));
        assertFalse(StageGraph.getDependencies(Stage.DOCS).contains(Stage.IMPLEMENT));
    }

    @Test
    @DisplayName("the release gate waits for both branches to finish")
    void releaseGateWaitsForBothBranches() {
        assertEquals(2, StageGraph.getDependencies(Stage.RELEASE_APPROVAL).size());
        assertTrue(StageGraph.getDependencies(Stage.RELEASE_APPROVAL).contains(Stage.TEST));
        assertTrue(StageGraph.getDependencies(Stage.RELEASE_APPROVAL).contains(Stage.DOCS));
    }

    @Test
    @DisplayName("nothing is released without the release gate")
    void releaseDependsOnlyOnTheReleaseGate() {
        assertEquals(Set.of(Stage.RELEASE_APPROVAL), StageGraph.getDependencies(Stage.RELEASE));
    }

    @Test
    @DisplayName("changing the design invalidates everything built on it")
    void downstreamOfDesignIsEverythingAfterIt() {
        Set<Stage> downstream = StageGraph.getDownstream(Stage.DESIGN);
        assertEquals(EnumSet.of(Stage.APPROVAL, Stage.IMPLEMENT, Stage.DOCS, Stage.TEST,
                Stage.RELEASE_APPROVAL, Stage.RELEASE), downstream);
        assertFalse(downstream.contains(Stage.REQUIREMENTS));
    }

    @Test
    @DisplayName("changing docs does not invalidate the code path")
    void downstreamOfDocsSkipsImplementation() {
        Set<Stage> downstream = StageGraph.getDownstream(Stage.DOCS);
        assertEquals(EnumSet.of(Stage.RELEASE_APPROVAL, Stage.RELEASE), downstream);
    }

    @Test
    @DisplayName("only the stages that change something need undoing")
    void onlyChangingStagesNeedUndoing() {
        assertTrue(StageGraph.isCompensatable(Stage.IMPLEMENT));
        assertFalse(StageGraph.isCompensatable(Stage.DOCS));
        assertFalse(StageGraph.isCompensatable(Stage.REQUIREMENTS));
    }
}

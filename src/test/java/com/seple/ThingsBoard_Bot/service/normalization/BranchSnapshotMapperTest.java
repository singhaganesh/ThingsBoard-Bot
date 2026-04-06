package com.seple.ThingsBoard_Bot.service.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.model.domain.NormalizedState;
import com.seple.ThingsBoard_Bot.support.FixtureLoader;

class BranchSnapshotMapperTest {

    private FullDataPayloadParser parser;
    private BranchSnapshotMapper mapper;
    private Map<String, Map<String, Object>> branches;

    @BeforeEach
    void setUp() throws Exception {
        parser = new FullDataPayloadParser();
        mapper = new BranchSnapshotMapper(new FieldPrecedenceResolver(new ValueNormalizer()), new ValueNormalizer());
        String json = FixtureLoader.load("fixtures/full_data_fixture.json");
        branches = parser.parse(json).branches();
    }

    @Test
    void shouldPreferBatteryStatusBatteryVoltageEvenWhenItIsZero() {
        BranchSnapshot snapshot = mapper.map(branches.get("BOI-CHANDANNAGAR"));

        assertEquals("BRANCH CHANDANNAGAR", snapshot.getIdentity().getBranchName());
        assertEquals(0.0, snapshot.getPower().getBatteryVoltage());
        assertEquals("battery_status_battery_voltage", snapshot.getPower().getBatteryVoltageSource());
    }

    @Test
    void shouldResolveTimeLockConflictAsOffline() {
        BranchSnapshot snapshot = mapper.map(branches.get("BOI-BHADRESWAR"));

        assertEquals(NormalizedState.OFFLINE, snapshot.getSubsystems().getTimeLock().getState());
    }
}

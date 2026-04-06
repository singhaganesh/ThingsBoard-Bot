package com.seple.ThingsBoard_Bot.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.service.normalization.BranchAliasIndex;
import com.seple.ThingsBoard_Bot.service.normalization.BranchSnapshotMapper;
import com.seple.ThingsBoard_Bot.service.normalization.FieldPrecedenceResolver;
import com.seple.ThingsBoard_Bot.service.normalization.FullDataPayloadParser;
import com.seple.ThingsBoard_Bot.service.normalization.ValueNormalizer;
import com.seple.ThingsBoard_Bot.support.FixtureLoader;

class QueryIntentResolverTest {

    private QueryIntentResolver resolver;
    private List<BranchSnapshot> snapshots;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new QueryIntentResolver(new BranchAliasIndex());
        FullDataPayloadParser parser = new FullDataPayloadParser();
        BranchSnapshotMapper mapper = new BranchSnapshotMapper(
                new FieldPrecedenceResolver(new ValueNormalizer()), new ValueNormalizer());

        String json = FixtureLoader.load("fixtures/full_data_fixture.json");
        snapshots = parser.parse(json).branches().values().stream()
                .map(mapper::map)
                .collect(Collectors.toList());
    }

    @Test
    void shouldResolveBranchBatteryQuestionDeterministically() {
        ResolvedQuery resolved = resolver.resolve("What is Tarakeshwar battery voltage?", snapshots, null);

        assertEquals(QueryIntent.BATTERY_VOLTAGE, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-TARAKESHWAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveCameraQuestionToCctvIntent() {
        ResolvedQuery resolved = resolver.resolve("How many cameras are online in Tarakeshwar?", snapshots, null);

        assertEquals(QueryIntent.CCTV_STATUS, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
    }

    @Test
    void shouldResolveBallyBazarAliasToSubsystemQuestion() {
        ResolvedQuery resolved = resolver.resolve("What is Bally Bazar IAS status?", snapshots, null);

        assertEquals(QueryIntent.SUBSYSTEM_STATUS, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-BALLYBAZAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
        assertEquals("ias", resolved.getTargetSystem());
    }

    @Test
    void shouldResolveFaultReasonIntent() {
        ResolvedQuery resolved = resolver.resolve("Why is Trendz fault?", snapshots, null);

        assertEquals(QueryIntent.FAULT_REASON, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("Trendz_Testing_Device", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveCameraDisconnectHistoryIntent() {
        ResolvedQuery resolved = resolver.resolve("Show historical camera disconnects for Chandannagar", snapshots, null);

        assertEquals(QueryIntent.CAMERA_DISCONNECT_HISTORY, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-CHANDANNAGAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldNotConfuseCurrentStatusWithSystemCurrent() {
        ResolvedQuery resolved = resolver.resolve("What is the current status of the Branch Chandannagar Gateway?", snapshots, null);

        assertEquals(QueryIntent.GATEWAY_STATUS, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-CHANDANNAGAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveCurrentlyActiveDevicesWithoutFallingIntoCurrentMetric() {
        ResolvedQuery resolved = resolver.resolve("Which devices are currently active on the Branch Bally Bazar Gateway?", snapshots, null);

        assertEquals(QueryIntent.ACTIVE_DEVICES, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-BALLYBAZAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveDisconnectStatusWithoutHistoryKeyword() {
        ResolvedQuery resolved = resolver.resolve("What is the Branch Chandannagar CCTV disconnect status right now?", snapshots, null);

        assertEquals(QueryIntent.CAMERA_DISCONNECT_HISTORY, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-CHANDANNAGAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveBatteryLowStatus() {
        ResolvedQuery resolved = resolver.resolve("What is the Branch Tarakeshwar Gateway battery low status right now?", snapshots, null);

        assertEquals(QueryIntent.BATTERY_LOW_STATUS, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-TARAKESHWAR", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveDoorStatusToSubsystemContext() {
        ResolvedQuery resolved = resolver.resolve("What is the Branch Tarakeshwar Time Lock door status right now?", snapshots, null);

        assertEquals(QueryIntent.DOOR_STATUS, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("timeLock", resolved.getTargetSystem());
    }

    @Test
    void shouldKeepAllBranchesQuestionGlobal() {
        ResolvedQuery resolved = resolver.resolve("What is the status of all devices in all branches right now?", snapshots, null);

        assertEquals(QueryIntent.GLOBAL_OVERVIEW, resolved.getIntent());
        assertEquals(true, resolved.isGlobal());
        assertEquals(null, resolved.getTargetBranch());
    }

    @Test
    void shouldKeepShowAllBranchDevicesQuestionGlobal() {
        ResolvedQuery resolved = resolver.resolve("Show status of all branch devices", snapshots, "BOI-TARAKESHWAR");

        assertEquals(QueryIntent.GLOBAL_OVERVIEW, resolved.getIntent());
        assertEquals(true, resolved.isGlobal());
        assertEquals(null, resolved.getTargetBranch());
    }

    @Test
    void shouldAskBranchClarificationForAllBranchBatteryVoltage() {
        ResolvedQuery resolved = resolver.resolve("all Branch Battery Voltage?", snapshots, null);

        assertEquals(QueryIntent.BATTERY_VOLTAGE, resolved.getIntent());
        assertEquals(false, resolved.isGlobal());
        assertEquals(true, resolved.isAmbiguous());
        assertEquals(null, resolved.getTargetBranch());
    }

    @Test
    void shouldAskBranchClarificationForAllBranchAcVoltage() {
        ResolvedQuery resolved = resolver.resolve("all Branch AC Voltage?", snapshots, null);

        assertEquals(QueryIntent.AC_VOLTAGE, resolved.getIntent());
        assertEquals(false, resolved.isGlobal());
        assertEquals(true, resolved.isAmbiguous());
        assertEquals(null, resolved.getTargetBranch());
    }

    @Test
    void shouldResolveLowBatteryPhraseVariant() {
        ResolvedQuery resolved = resolver.resolve("Is there a low battery warning on the Branch liluah Gateway?", snapshots, null);

        assertEquals(QueryIntent.BATTERY_LOW_STATUS, resolved.getIntent());
        assertNotNull(resolved.getTargetBranch());
        assertEquals("BOI-LILUAH", resolved.getTargetBranch().getIdentity().getTechnicalId());
    }

    @Test
    void shouldResolveCctvHddErrorQuestion() {
        ResolvedQuery resolved = resolver.resolve("What is the Branch dankuni CCTV HDD Error right now?", snapshots, null);

        assertEquals(QueryIntent.CCTV_HDD_ERROR_STATUS, resolved.getIntent());
        assertEquals("cctv", resolved.getTargetSystem());
        assertNotNull(resolved.getTargetBranch());
    }

    @Test
    void shouldResolveSubsystemFaultQuestionForBas() {
        ResolvedQuery resolved = resolver.resolve("What is the Branch dankuni BAS fault status right now?", snapshots, null);

        assertEquals(QueryIntent.SUBSYSTEM_FAULT_STATUS, resolved.getIntent());
        assertEquals("bas", resolved.getTargetSystem());
    }

    @Test
    void shouldResolveSubsystemAlarmQuestionForTimeLock() {
        ResolvedQuery resolved = resolver.resolve("What is the Branch dankuni Time Lock alarm status right now?", snapshots, null);

        assertEquals(QueryIntent.SUBSYSTEM_ALARM_STATUS, resolved.getIntent());
        assertEquals("timeLock", resolved.getTargetSystem());
    }
}

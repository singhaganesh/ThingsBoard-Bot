package com.seple.ThingsBoard_Bot.service.query;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;
import com.seple.ThingsBoard_Bot.service.normalization.BranchSnapshotMapper;
import com.seple.ThingsBoard_Bot.service.normalization.FieldPrecedenceResolver;
import com.seple.ThingsBoard_Bot.service.normalization.FullDataPayloadParser;
import com.seple.ThingsBoard_Bot.service.normalization.ValueNormalizer;
import com.seple.ThingsBoard_Bot.support.FixtureLoader;

class DeterministicAnswerServiceTest {

    private DeterministicAnswerService answerService;
    private List<BranchSnapshot> snapshots;

    @BeforeEach
    void setUp() throws Exception {
        answerService = new DeterministicAnswerService(new AnswerTemplateService());
        FullDataPayloadParser parser = new FullDataPayloadParser();
        BranchSnapshotMapper mapper = new BranchSnapshotMapper(
                new FieldPrecedenceResolver(new ValueNormalizer()), new ValueNormalizer());

        String json = FixtureLoader.load("fixtures/full_data_fixture.json");
        snapshots = parser.parse(json).branches().values().stream()
                .map(mapper::map)
                .collect(Collectors.toList());
    }

    @Test
    void shouldGenerateGlobalOverviewWithoutLlm() {
        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.GLOBAL_OVERVIEW)
                .originalQuestion("List all branches")
                .global(true)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("**Total:"));
        assertTrue(answer.contains("Online:"));
    }

    @Test
    void shouldGenerateCameraStatusFromStructuredSnapshot() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-LILUAH".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CCTV_STATUS)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("12"));
        assertTrue(answer.contains("ONLINE"));
    }

    @Test
    void shouldExplainTrendzFaultReason() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "Trendz_Testing_Device".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.FAULT_REASON)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("fault indication"));
        assertTrue(answer.contains("fire alarm fault indicator"));
    }

    @Test
    void shouldReturnNoHistoricalDisconnectsWhenHistoryArraysAreEmpty() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-CHANDANNAGAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CAMERA_DISCONNECT_HISTORY)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("No historical camera disconnects found"));
    }

    @Test
    void shouldUseValidCameraObjectsOnlyForTarakeshwarTotals() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-TARAKESHWAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CCTV_STATUS)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("14 of 15"));
    }

    @Test
    void shouldReturnGatewayStatusForCurrentStatusQuestionInsteadOfSystemCurrent() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-CHANDANNAGAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.GATEWAY_STATUS)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Gateway status"));
        assertTrue(answer.contains("ONLINE"));
    }

    @Test
    void shouldReturnActiveDevicesInsteadOfSystemCurrentForCurrentlyActivePrompt() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-BALLYBAZAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.ACTIVE_DEVICES)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("CCTV DVR"));
        assertTrue(answer.contains("IAS Panel"));
    }

    @Test
    void shouldReturnBatteryLowStatus() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-TARAKESHWAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.BATTERY_LOW_STATUS)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Battery Low Status"));
        assertTrue(answer.contains("NORMAL"));
    }

    @Test
    void shouldReturnOfflineDevicesForBhadreswar() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-BHADRESWAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.OFFLINE_DEVICES)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Offline Devices"));
        assertTrue(answer.contains("Time Lock"));
    }

    @Test
    void shouldReturnConnectedDevicesForTarakeshwar() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-TARAKESHWAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CONNECTED_DEVICES)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Connected Devices"));
        assertTrue(answer.contains("CCTV DVR"));
        assertTrue(answer.contains("Time Lock"));
    }

    @Test
    void shouldReturnGroundedNetworkStatus() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-TARAKESHWAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.NETWORK_STATUS)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Network Status: ON"));
    }

    @Test
    void shouldReturnCctvHddInformation() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-CHANDANNAGAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CCTV_HDD_INFO)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("CCTV HDD Information"));
        assertTrue(answer.contains("Slot 1"));
        assertTrue(answer.contains("Slot 4"));
    }

    @Test
    void shouldReturnCctvRecordingInformation() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-CHANDANNAGAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CCTV_RECORDING_INFO)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("CCTV Recording Information"));
        assertTrue(answer.contains("12 channel(s)"));
    }

    @Test
    void shouldReturnTimeLockDoorStatus() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-TARAKESHWAR".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.DOOR_STATUS)
                .targetBranch(target)
                .targetSystem("timeLock")
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Time Lock Door Status"));
        assertTrue(answer.contains("CLOSE"));
    }

    @Test
    void shouldReturnUnavailableAccessControlUserCountInsteadOfStatusOnly() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "Trendz_Testing_Device".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.ACCESS_CONTROL_USER_COUNT)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("user count is not available"));
        assertTrue(answer.contains("ONLINE"));
    }

    @Test
    void shouldReturnUnavailableAccessControlDeviceInfoInsteadOfGenericStatus() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "Trendz_Testing_Device".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.ACCESS_CONTROL_DEVICE_INFO)
                .targetBranch(target)
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("device information is not available"));
        assertTrue(answer.contains("ONLINE"));
    }

    @Test
    void shouldReturnCctvHddErrorStatusInsteadOfCameraCount() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-DANKUNI".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.CCTV_HDD_ERROR_STATUS)
                .targetBranch(target)
                .targetSystem("cctv")
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("CCTV HDD Error Status"));
        assertTrue(answer.contains("NORMAL"));
    }

    @Test
    void shouldReturnNotInstalledForTimeLockAlarmStatus() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-DANKUNI".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.SUBSYSTEM_ALARM_STATUS)
                .targetBranch(target)
                .targetSystem("timeLock")
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Time Lock Alarm Status"));
        assertTrue(answer.contains("NOT INSTALLED"));
    }

    @Test
    void shouldReturnNotInstalledForAccessControlAlarmStatus() {
        BranchSnapshot target = snapshots.stream()
                .filter(snapshot -> "BOI-DANKUNI".equals(snapshot.getIdentity().getTechnicalId()))
                .findFirst()
                .orElseThrow();

        ResolvedQuery query = ResolvedQuery.builder()
                .intent(QueryIntent.SUBSYSTEM_ALARM_STATUS)
                .targetBranch(target)
                .targetSystem("accessControl")
                .deterministic(true)
                .confidence(1.0)
                .build();

        String answer = answerService.answer(query, snapshots);

        assertTrue(answer.contains("Access Control Alarm Status"));
        assertTrue(answer.contains("NOT INSTALLED"));
    }
}

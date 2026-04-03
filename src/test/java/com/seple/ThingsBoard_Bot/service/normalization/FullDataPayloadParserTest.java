package com.seple.ThingsBoard_Bot.service.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.seple.ThingsBoard_Bot.support.FixtureLoader;

class FullDataPayloadParserTest {

    private final FullDataPayloadParser parser = new FullDataPayloadParser();

    @Test
    void shouldParseFullDataIntoBranchBuckets() throws Exception {
        String json = FixtureLoader.load("fixtures/full_data_fixture.json");

        FullDataPayloadParser.ParsedPayload parsed = parser.parse(json);

        assertEquals(11, parsed.branches().size());
        assertTrue(parsed.branches().containsKey("BOI-CHANDANNAGAR"));
        assertTrue(parsed.branches().get("BOI-TARAKESHWAR").containsKey("device_name"));
        assertFalse(parsed.warnings().isEmpty());
    }
}

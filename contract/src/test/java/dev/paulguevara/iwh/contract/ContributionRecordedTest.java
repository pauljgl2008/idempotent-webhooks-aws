package dev.paulguevara.iwh.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ContributionRecordedTest {

    static final String VALID = """
            {"providerId":"acme-payroll","eventId":"evt-001","employeeId":"emp-42",
             "campaignId":"camp-7","amountMinor":2500,"currency":"USD",
             "occurredAt":"2026-11-01T14:00:00Z"}""";

    @Test
    void parsesAValidEvent() {
        var event = ContributionRecorded.parse(VALID);

        assertThat(event.providerId()).isEqualTo("acme-payroll");
        assertThat(event.eventId()).isEqualTo("evt-001");
        assertThat(event.employeeId()).isEqualTo("emp-42");
        assertThat(event.campaignId()).isEqualTo("camp-7");
        assertThat(event.amountMinor()).isEqualTo(2500L);
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-01T14:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[]",
            // missing eventId
            """
            {"providerId":"p","employeeId":"e","campaignId":"c","amountMinor":1,"currency":"USD","occurredAt":"2026-11-01T14:00:00Z"}""",
            // amount as a string
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":"1","currency":"USD","occurredAt":"2026-11-01T14:00:00Z"}""",
            // fractional amount
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":1.5,"currency":"USD","occurredAt":"2026-11-01T14:00:00Z"}""",
            // zero amount
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":0,"currency":"USD","occurredAt":"2026-11-01T14:00:00Z"}""",
            // above the maximum
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":1000000001,"currency":"USD","occurredAt":"2026-11-01T14:00:00Z"}""",
            // lowercase currency
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":1,"currency":"usd","occurredAt":"2026-11-01T14:00:00Z"}""",
            // bad timestamp
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":1,"currency":"USD","occurredAt":"yesterday"}""",
            // separator character inside an id would break the composite keys
            """
            {"providerId":"p#q","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":1,"currency":"USD","occurredAt":"2026-11-01T14:00:00Z"}""",
            // unknown field
            """
            {"providerId":"p","eventId":"x","employeeId":"e","campaignId":"c","amountMinor":1,"currency":"USD","occurredAt":"2026-11-01T14:00:00Z","refund":true}"""
    })
    void rejectsInvalidEvents(String body) {
        assertThatThrownBy(() -> ContributionRecorded.parse(body))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void hashIsTheSha256OfTheCanonicalForm() throws Exception {
        String canonical = String.join("\n",
                "providerId=acme-payroll",
                "eventId=evt-001",
                "employeeId=emp-42",
                "campaignId=camp-7",
                "amountMinor=2500",
                "currency=USD",
                "occurredAt=2026-11-01T14:00:00Z");
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(ContributionRecorded.parse(VALID).payloadHash()).isEqualTo(expected);
    }

    @Test
    void hashIgnoresKeyOrderAndWhitespace() {
        String reordered = """
                {  "occurredAt" : "2026-11-01T14:00:00.000Z", "currency":"USD","amountMinor":2500,
                   "campaignId":"camp-7","employeeId":"emp-42","eventId":"evt-001","providerId":"acme-payroll" }""";

        assertThat(ContributionRecorded.parse(reordered).payloadHash())
                .isEqualTo(ContributionRecorded.parse(VALID).payloadHash());
    }

    @Test
    void hashChangesWhenTheAmountChanges() {
        String other = VALID.replace("2500", "2600");

        assertThat(ContributionRecorded.parse(other).payloadHash())
                .isNotEqualTo(ContributionRecorded.parse(VALID).payloadHash());
    }

    @Test
    void exposesTheCompositeKeys() {
        var event = ContributionRecorded.parse(VALID);

        assertThat(event.idempotencyKey()).isEqualTo("acme-payroll#evt-001");
        assertThat(event.ledgerKey()).isEqualTo("emp-42#camp-7");
        assertThat(event.balanceKey()).isEqualTo("emp-42#camp-7#USD");
    }
}

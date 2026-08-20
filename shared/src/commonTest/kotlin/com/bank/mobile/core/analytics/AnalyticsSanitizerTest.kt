package com.bank.mobile.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalyticsSanitizerTest {
    @Test fun preservesOnlyContractAttributes() {
        val sanitized = AnalyticsSanitizer().sanitize(
            AnalyticsEvent("transfer", mapOf("status" to "completed", "account" to "secret")),
        )
        assertEquals("completed", sanitized.attributes["status"])
        assertFalse("account" in sanitized.attributes)
    }

    @Test fun contractNormalizesValuesAndReportsRemovedAttributes() {
        val contract = AnalyticsEventContract(
            name = "transfer_result",
            purpose = AnalyticsPurpose.RELIABILITY,
            attributes = listOf(
                AnalyticsAttributeRule(
                    key = "status",
                    type = AnalyticsValueType.ENUM,
                    sensitivity = AnalyticsSensitivity.INTERNAL,
                    required = true,
                    allowedValues = setOf("completed", "failed"),
                ),
            ),
        )
        val event = AnalyticsEvent(
            name = "transfer_result",
            attributes = mapOf("status" to " COMPLETED ", "account" to "account-42"),
        )
        val policy = AnalyticsPolicy(listOf(contract))
        val decision = policy.evaluate(event)
        val report = AnalyticsSanitizer().sanitizeWithReport(event, contract, decision)

        assertFalse(decision.accepted)
        assertEquals("completed", report.event.attributes["status"])
        assertEquals(setOf("account"), report.removedKeys)
        assertTrue("status" in report.normalizedKeys)
    }

    @Test fun policyIdentifiesMissingAndSensitiveData() {
        val contract = AnalyticsEventContract(
            name = "login_result",
            purpose = AnalyticsPurpose.SECURITY,
            attributes = listOf(
                AnalyticsAttributeRule(
                    key = "result",
                    type = AnalyticsValueType.ENUM,
                    sensitivity = AnalyticsSensitivity.INTERNAL,
                    required = true,
                    allowedValues = setOf("success", "failure"),
                ),
                AnalyticsAttributeRule(
                    key = "opaque_subject",
                    type = AnalyticsValueType.OPAQUE_IDENTIFIER,
                    sensitivity = AnalyticsSensitivity.PSEUDONYMOUS,
                ),
            ),
        )
        val decision = AnalyticsPolicy(listOf(contract)).evaluate(
            AnalyticsEvent("login_result", mapOf("opaque_subject" to "subject-1")),
        )

        assertFalse(decision.accepted)
        assertTrue(decision.issues.any { it is AnalyticsPolicyIssue.MissingAttribute })
        assertTrue(decision.issues.any { it is AnalyticsPolicyIssue.DisallowedSensitivity })
    }

    @Test fun policyTrackerForwardsOnlyContractCompliantPayload() {
        val forwarded = mutableListOf<AnalyticsEvent>()
        val rejected = mutableListOf<AnalyticsPolicyDecision>()
        val contract = AnalyticsEventContract(
            name = "refresh_result",
            purpose = AnalyticsPurpose.RELIABILITY,
            attributes = listOf(
                AnalyticsAttributeRule(
                    key = "result",
                    type = AnalyticsValueType.ENUM,
                    sensitivity = AnalyticsSensitivity.INTERNAL,
                    required = true,
                    allowedValues = setOf("success", "failure"),
                ),
            ),
        )
        val tracker = PolicyAnalyticsTracker(
            delegate = AnalyticsTracker(forwarded::add),
            policy = AnalyticsPolicy(listOf(contract)),
            onRejected = { _, decision -> rejected += decision },
        )

        tracker.track(AnalyticsEvent("refresh_result", mapOf("result" to "success")))
        tracker.track(AnalyticsEvent("unknown_event"))

        assertEquals(1, forwarded.size)
        assertEquals("success", forwarded.single().attributes["result"])
        assertEquals(1, rejected.size)
        assertIs<AnalyticsPolicyIssue.UnknownEvent>(rejected.single().issues.single())
    }
}

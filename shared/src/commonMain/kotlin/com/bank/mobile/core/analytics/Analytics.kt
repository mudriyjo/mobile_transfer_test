package com.bank.mobile.core.analytics

/** A transport-neutral analytics event produced by a feature. */
data class AnalyticsEvent(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
)

fun interface AnalyticsTracker {
    fun track(event: AnalyticsEvent)
}

/**
 * Describes why a value is collected. Sensitivity and purpose are deliberately
 * independent: an operational metric may still be sensitive and therefore
 * unsuitable for a third-party destination.
 */
enum class AnalyticsPurpose {
    PRODUCT,
    RELIABILITY,
    SECURITY,
    AUDIT,
}

enum class AnalyticsSensitivity {
    PUBLIC,
    INTERNAL,
    PSEUDONYMOUS,
    SENSITIVE,
}

enum class AnalyticsValueType {
    ENUM,
    BOOLEAN,
    INTEGER,
    DURATION_MILLIS,
    ERROR_CODE,
    OPAQUE_IDENTIFIER,
    TEXT,
}

data class AnalyticsAttributeRule(
    val key: String,
    val type: AnalyticsValueType,
    val sensitivity: AnalyticsSensitivity,
    val required: Boolean = false,
    val maximumLength: Int = DEFAULT_MAXIMUM_LENGTH,
    val allowedValues: Set<String> = emptySet(),
) {
    init {
        require(key.isNotBlank()) { "Analytics attribute key must not be blank" }
        require(maximumLength > 0) { "maximumLength must be positive" }
        require(allowedValues.none(String::isBlank)) { "Allowed values must not be blank" }
    }

    fun normalize(value: String): String = when (type) {
        AnalyticsValueType.BOOLEAN -> value.trim().lowercase()
        AnalyticsValueType.ENUM,
        AnalyticsValueType.ERROR_CODE,
        -> value.trim().lowercase().replace(' ', '_')

        AnalyticsValueType.INTEGER,
        AnalyticsValueType.DURATION_MILLIS,
        AnalyticsValueType.OPAQUE_IDENTIFIER,
        AnalyticsValueType.TEXT,
        -> value.trim()
    }

    fun accepts(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.length > maximumLength) return false
        if (allowedValues.isNotEmpty() && value !in allowedValues) return false
        return when (type) {
            AnalyticsValueType.BOOLEAN -> value == "true" || value == "false"
            AnalyticsValueType.INTEGER,
            AnalyticsValueType.DURATION_MILLIS,
            -> value.toLongOrNull() != null

            AnalyticsValueType.ENUM,
            AnalyticsValueType.ERROR_CODE,
            AnalyticsValueType.OPAQUE_IDENTIFIER,
            AnalyticsValueType.TEXT,
            -> true
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_LENGTH = 96
    }
}

data class AnalyticsEventContract(
    val name: String,
    val version: Int = 1,
    val purpose: AnalyticsPurpose,
    val attributes: List<AnalyticsAttributeRule>,
    val allowUnregisteredAttributes: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Analytics event name must not be blank" }
        require(version > 0) { "Analytics contract version must be positive" }
        require(attributes.map(AnalyticsAttributeRule::key).distinct().size == attributes.size) {
            "Analytics attribute rules must have unique keys"
        }
    }

    private val rulesByKey = attributes.associateBy(AnalyticsAttributeRule::key)

    fun ruleFor(key: String): AnalyticsAttributeRule? = rulesByKey[key]

    val requiredKeys: Set<String>
        get() = attributes.filter(AnalyticsAttributeRule::required).mapTo(mutableSetOf()) {
            it.key
        }
}

sealed interface AnalyticsPolicyIssue {
    data class UnknownEvent(val eventName: String) : AnalyticsPolicyIssue
    data class UnknownAttribute(val key: String) : AnalyticsPolicyIssue
    data class MissingAttribute(val key: String) : AnalyticsPolicyIssue
    data class InvalidAttribute(val key: String) : AnalyticsPolicyIssue
    data class DisallowedSensitivity(
        val key: String,
        val sensitivity: AnalyticsSensitivity,
    ) : AnalyticsPolicyIssue
}

data class AnalyticsPolicyDecision(
    val accepted: Boolean,
    val issues: List<AnalyticsPolicyIssue>,
) {
    val rejectedKeys: Set<String>
        get() = issues.mapNotNullTo(mutableSetOf()) { issue ->
            when (issue) {
                is AnalyticsPolicyIssue.DisallowedSensitivity -> issue.key
                is AnalyticsPolicyIssue.InvalidAttribute -> issue.key
                is AnalyticsPolicyIssue.MissingAttribute -> issue.key
                is AnalyticsPolicyIssue.UnknownAttribute -> issue.key
                is AnalyticsPolicyIssue.UnknownEvent -> null
            }
        }
}

/** Evaluates an event against the contract of a particular telemetry sink. */
class AnalyticsPolicy(
    contracts: Collection<AnalyticsEventContract>,
    private val maximumSensitivity: AnalyticsSensitivity = AnalyticsSensitivity.INTERNAL,
) {
    private val contractsByName = contracts.associateBy(AnalyticsEventContract::name)

    init {
        require(contractsByName.size == contracts.size) { "Analytics contracts must have unique names" }
    }

    fun contractFor(eventName: String): AnalyticsEventContract? = contractsByName[eventName]

    fun evaluate(event: AnalyticsEvent): AnalyticsPolicyDecision {
        val contract = contractsByName[event.name]
            ?: return AnalyticsPolicyDecision(
                accepted = false,
                issues = listOf(AnalyticsPolicyIssue.UnknownEvent(event.name)),
            )
        val issues = buildList {
            contract.requiredKeys
                .filterNot(event.attributes::containsKey)
                .forEach { add(AnalyticsPolicyIssue.MissingAttribute(it)) }

            event.attributes.forEach { (key, rawValue) ->
                val rule = contract.ruleFor(key)
                if (rule == null) {
                    if (!contract.allowUnregisteredAttributes) {
                        add(AnalyticsPolicyIssue.UnknownAttribute(key))
                    }
                    return@forEach
                }
                val normalizedValue = rule.normalize(rawValue)
                if (!rule.accepts(normalizedValue)) {
                    add(AnalyticsPolicyIssue.InvalidAttribute(key))
                }
                if (rule.sensitivity > maximumSensitivity) {
                    add(AnalyticsPolicyIssue.DisallowedSensitivity(key, rule.sensitivity))
                }
            }
        }
        return AnalyticsPolicyDecision(accepted = issues.isEmpty(), issues = issues)
    }
}

data class AnalyticsSanitizationReport(
    val event: AnalyticsEvent,
    val removedKeys: Set<String>,
    val normalizedKeys: Set<String>,
    val policyDecision: AnalyticsPolicyDecision? = null,
)

/**
 * Sink-side attribute filtering. Feature producers remain responsible for
 * emitting contract-compliant events; this class is a final boundary guard.
 */
class AnalyticsSanitizer(
    private val allowedKeys: Set<String> = DEFAULT_ALLOWED_KEYS,
    private val maximumValueLength: Int = DEFAULT_MAXIMUM_VALUE_LENGTH,
) {
    init {
        require(allowedKeys.none(String::isBlank)) { "Allowed analytics keys must not be blank" }
        require(maximumValueLength > 0) { "maximumValueLength must be positive" }
    }

    fun sanitize(event: AnalyticsEvent): AnalyticsEvent = sanitizeWithReport(event).event

    fun sanitizeWithReport(
        event: AnalyticsEvent,
        contract: AnalyticsEventContract? = null,
        policyDecision: AnalyticsPolicyDecision? = null,
    ): AnalyticsSanitizationReport {
        val acceptedRules = contract?.attributes?.associateBy(AnalyticsAttributeRule::key).orEmpty()
        val removedKeys = mutableSetOf<String>()
        val normalizedKeys = mutableSetOf<String>()
        val attributes = buildMap {
            event.attributes.forEach { (key, rawValue) ->
                val rule = acceptedRules[key]
                val acceptedByKeyPolicy = key in allowedKeys || rule != null
                val acceptedByDecision = key !in policyDecision.orEmptyRejectedKeys()
                if (!acceptedByKeyPolicy || !acceptedByDecision) {
                    removedKeys += key
                    return@forEach
                }

                val normalized = (rule?.normalize(rawValue) ?: rawValue.trim())
                    .take(rule?.maximumLength ?: maximumValueLength)
                if (normalized != rawValue) normalizedKeys += key
                if (normalized.isBlank()) {
                    removedKeys += key
                } else {
                    put(key, normalized)
                }
            }
        }
        return AnalyticsSanitizationReport(
            event = event.copy(attributes = attributes),
            removedKeys = removedKeys,
            normalizedKeys = normalizedKeys,
            policyDecision = policyDecision,
        )
    }

    private fun AnalyticsPolicyDecision?.orEmptyRejectedKeys(): Set<String> =
        this?.rejectedKeys.orEmpty()

    companion object {
        val DEFAULT_ALLOWED_KEYS = setOf(
            "flow",
            "status",
            "error_code",
            "network_type",
            "duration_ms",
            "source",
            "result",
        )
        const val DEFAULT_MAXIMUM_VALUE_LENGTH = 96
    }
}

/** Applies a declared policy immediately before handing data to a concrete sink. */
class PolicyAnalyticsTracker(
    private val delegate: AnalyticsTracker,
    private val policy: AnalyticsPolicy,
    private val sanitizer: AnalyticsSanitizer = AnalyticsSanitizer(),
    private val onRejected: (AnalyticsEvent, AnalyticsPolicyDecision) -> Unit = { _, _ -> },
) : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) {
        val decision = policy.evaluate(event)
        if (!decision.accepted) onRejected(event, decision)
        val contract = policy.contractFor(event.name) ?: return
        val report = sanitizer.sanitizeWithReport(event, contract, decision)
        if (contract.requiredKeys.all(report.event.attributes::containsKey)) {
            delegate.track(report.event)
        }
    }
}

/** Adds low-cardinality context without allowing it to replace feature values. */
class ContextualAnalyticsTracker(
    private val delegate: AnalyticsTracker,
    private val context: () -> Map<String, String>,
) : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) {
        delegate.track(event.copy(attributes = context() + event.attributes))
    }
}

class CompositeAnalyticsTracker(
    private val delegates: List<AnalyticsTracker>,
) : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) {
        delegates.forEach { tracker -> tracker.track(event) }
    }
}

object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) = Unit
}

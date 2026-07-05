/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads device-profile documents ({@code zigbee-profiles.json} + the user override
 * file) into indexed {@link ProfileEntry} rows.
 *
 * <p><strong>§H — the loader owns {@code schemaVersion}:</strong> the document
 * carries {@code {"schemaVersion": {"major": M, "minor": m}}}; an unknown MAJOR is
 * a fail-closed {@link ProfileLoadException}, an unknown MINOR is
 * tolerated-additive (unrecognized fields are ignored). The {@link DeviceProfile}
 * record carries no version — format evolution is the loader's concern.
 *
 * <p><strong>§F — index-first:</strong> {@code profileId}, {@code matches}, and
 * {@code priority} parse eagerly (the match index); the profile body materializes
 * lazily on first match. A malformed body therefore surfaces on materialization,
 * naming the profile — never as a silent partial load.
 *
 * <p><strong>§C — collision rules:</strong> duplicate ids within one load are a
 * loader error. Bare ids are first-party, dotted {@code publisher.profile} ids are
 * third-party; the two namespaces are distinct key spaces and never merge.
 *
 * <p><strong>§D — no eval-in-data:</strong> converters are resolved by NAME via
 * {@link StandardValueConverters}; no expression, script, or code rides in profile
 * data.
 *
 * <p>Jackson stays interior (tree model only — no reflective databind, no Jackson
 * type on any exported signature; the D-M92-1 pattern).
 *
 * <p>Thread-safe: stateless — every call parses its own document.
 */
final class ZigbeeProfileLoader {

    /** The supported profile-document schema major version (§H). */
    static final int SUPPORTED_SCHEMA_MAJOR = 1;

    /** The bundled corpus resource (classpath). */
    static final String BUNDLED_RESOURCE = "/zigbee-profiles.json";

    private static final Logger log = LoggerFactory.getLogger(ZigbeeProfileLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Creates a loader. Performs no I/O. */
    ZigbeeProfileLoader() {
    }

    /**
     * Loads the bundled {@code zigbee-profiles.json} corpus from the classpath.
     *
     * @return the indexed entries, source {@link ProfileSource#BUNDLED}
     * @throws ProfileLoadException if the resource is missing or does not load
     */
    List<ProfileEntry> loadBundled() {
        try (InputStream in = ZigbeeProfileLoader.class
                .getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new ProfileLoadException(
                        "Bundled profile resource " + BUNDLED_RESOURCE
                                + " is missing from the adapter jar");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    ProfileSource.BUNDLED);
        } catch (IOException e) {
            throw new ProfileLoadException(
                    "Bundled profile resource " + BUNDLED_RESOURCE
                            + " failed to read: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the user override file (the {@code integrations.zigbee.profiles_path}
     * channel — the path arrives injected; the config-key binding is M9.4's).
     *
     * @param path the user profile file, never {@code null}
     * @return the indexed entries, source {@link ProfileSource#USER}
     * @throws ProfileLoadException if the file is missing or does not load —
     *         a configured-but-broken user file fails closed, never silently
     */
    List<ProfileEntry> loadUserProfiles(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8),
                    ProfileSource.USER);
        } catch (IOException e) {
            throw new ProfileLoadException(
                    "User profile file " + path + " failed to read: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Parses one profile document into indexed entries.
     *
     * @param json the document text, never {@code null}
     * @param source the load source, never {@code null}
     * @return the indexed entries in document order
     * @throws ProfileLoadException on schema-major mismatch, duplicate ids,
     *         malformed criteria, or a document that is not valid JSON
     */
    List<ProfileEntry> parse(String json, ProfileSource source) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ProfileLoadException(
                    "Profile document is not valid JSON: " + e.getMessage(), e);
        }
        validateSchemaVersion(root);

        JsonNode profiles = root.get("profiles");
        if (profiles == null || !profiles.isArray()) {
            throw new ProfileLoadException(
                    "Profile document requires a 'profiles' array");
        }

        List<ProfileEntry> entries = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (JsonNode profileNode : profiles) {
            String profileId = requiredText(profileNode, "profileId", "profile");
            if (!seenIds.add(profileId)) {
                throw new ProfileLoadException(
                        "Duplicate profile id '" + profileId + "' within one load; "
                                + "profile ids are unique per document (§C)");
            }
            Set<MatchCriteria> criteria = parseCriteria(profileNode, profileId);
            int priority = profileNode.path("priority").asInt(0);
            entries.add(new ProfileEntry(profileId, criteria, priority, source,
                    profileNode, body -> materialize(profileId, body)));
        }
        log.debug("Profile document parsed: source={} profiles={}", source,
                entries.size());
        return entries;
    }

    private static void validateSchemaVersion(JsonNode root) {
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isObject()
                || !version.path("major").isInt()) {
            throw new ProfileLoadException(
                    "Profile document requires a schemaVersion object with an "
                            + "integer 'major' field (§H)");
        }
        int major = version.get("major").asInt();
        if (major != SUPPORTED_SCHEMA_MAJOR) {
            throw new ProfileLoadException(
                    "Profile document schema major version " + major
                            + " is not supported; supported major: "
                            + SUPPORTED_SCHEMA_MAJOR
                            + " (unknown major fails closed, §H)");
        }
        int minor = version.path("minor").asInt(0);
        if (minor > 0) {
            log.debug("Profile document minor version {} tolerated-additive", minor);
        }
    }

    private static Set<MatchCriteria> parseCriteria(JsonNode profileNode,
            String profileId) {
        JsonNode matches = profileNode.get("matches");
        if (matches == null || !matches.isArray() || matches.isEmpty()) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' requires a non-empty 'matches' array");
        }
        Set<MatchCriteria> criteria = new LinkedHashSet<>();
        for (JsonNode match : matches) {
            String type = requiredText(match, "type", profileId);
            criteria.add(switch (type) {
                case "exact_model" -> new ExactModel(
                        requiredText(match, "manufacturer", profileId),
                        requiredText(match, "model", profileId));
                case "model_wildcard" -> new ModelWildcard(
                        requiredText(match, "manufacturer", profileId),
                        requiredText(match, "modelPrefix", profileId));
                case "fingerprint" -> parseFingerprint(match, profileId);
                default -> throw new ProfileLoadException(
                        "Profile '" + profileId + "' carries unknown match criteria "
                                + "type '" + type + "'; supported: exact_model, "
                                + "model_wildcard, fingerprint");
            });
        }
        return criteria;
    }

    private static Fingerprint parseFingerprint(JsonNode match, String profileId) {
        JsonNode endpoints = match.get("endpoints");
        if (endpoints == null || !endpoints.isArray() || endpoints.isEmpty()) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' fingerprint requires a non-empty "
                            + "'endpoints' array");
        }
        List<EndpointSignature> signatures = new ArrayList<>();
        for (JsonNode endpoint : endpoints) {
            signatures.add(new EndpointSignature(
                    flexibleInt(endpoint, "profileId", profileId),
                    flexibleInt(endpoint, "deviceType", profileId),
                    intSet(endpoint.get("inClusters"), profileId),
                    intSet(endpoint.get("outClusters"), profileId)));
        }
        return new Fingerprint(
                requiredText(match, "manufacturer", profileId),
                requiredText(match, "model", profileId),
                signatures);
    }

    private DeviceProfile materialize(String profileId, JsonNode node) {
        try {
            return new DeviceProfile(
                    profileId,
                    parseCriteria(node, profileId),
                    DeviceCategory.valueOf(requiredText(node, "category", profileId)),
                    parseClusterOverrides(node.get("clusterOverrides"), profileId),
                    parseReportingOverrides(node.get("reportingOverrides"), profileId),
                    optionalText(node, "manufacturerCodec"),
                    parseStringSet(node.get("interviewSkips")),
                    parseTuyaDatapoints(node.get("tuyaDatapoints"), profileId),
                    parseInitializationWrites(node.get("initializationWrites"),
                            profileId),
                    parseConfirmation(node.get("confirmation"), profileId));
        } catch (ProfileLoadException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' failed to materialize: "
                            + e.getMessage(), e);
        }
    }

    private static Map<Integer, ClusterOverride> parseClusterOverrides(
            JsonNode overrides, String profileId) {
        if (overrides == null || overrides.isNull()) {
            return null;
        }
        Map<Integer, ClusterOverride> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            int clusterId = parseFlexibleInt(field.getKey(), profileId,
                    "clusterOverrides key");
            JsonNode override = field.getValue();
            Map<Integer, String> attributeOverrides = new HashMap<>();
            JsonNode attributes = override.get("attributeOverrides");
            if (attributes != null && attributes.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> attrs = attributes.fields();
                while (attrs.hasNext()) {
                    Map.Entry<String, JsonNode> attr = attrs.next();
                    attributeOverrides.put(
                            parseFlexibleInt(attr.getKey(), profileId,
                                    "attributeOverrides key"),
                            attr.getValue().asText());
                }
            }
            result.put(clusterId, new ClusterOverride(clusterId, attributeOverrides,
                    override.path("disableDefaultHandler").asBoolean(false)));
        }
        return result;
    }

    private static Map<Integer, ReportingOverride> parseReportingOverrides(
            JsonNode overrides, String profileId) {
        if (overrides == null || overrides.isNull()) {
            return null;
        }
        Map<Integer, ReportingOverride> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            int clusterId = parseFlexibleInt(field.getKey(), profileId,
                    "reportingOverrides key");
            JsonNode override = field.getValue();
            result.put(clusterId, new ReportingOverride(clusterId,
                    override.path("minInterval").asInt(),
                    override.path("maxInterval").asInt(),
                    override.path("reportableChange").asInt()));
        }
        return result;
    }

    private static List<TuyaDatapointMapping> parseTuyaDatapoints(JsonNode datapoints,
            String profileId) {
        if (datapoints == null || datapoints.isNull()) {
            return null;
        }
        List<TuyaDatapointMapping> result = new ArrayList<>();
        for (JsonNode dp : datapoints) {
            result.add(new TuyaDatapointMapping(
                    dp.path("dpId").asInt(),
                    requiredText(dp, "attributeKey", profileId),
                    TuyaDpType.valueOf(requiredText(dp, "type", profileId)),
                    StandardValueConverters.byName(
                            requiredText(dp, "converter", profileId))));
        }
        return result;
    }

    private static List<InitializationWrite> parseInitializationWrites(
            JsonNode writes, String profileId) {
        if (writes == null || writes.isNull()) {
            return null;
        }
        List<InitializationWrite> result = new ArrayList<>();
        for (JsonNode write : writes) {
            result.add(new InitializationWrite(
                    flexibleInt(write, "endpoint", profileId),
                    flexibleInt(write, "cluster", profileId),
                    flexibleInt(write, "attribute", profileId),
                    flexibleInt(write, "dataType", profileId),
                    scalarValue(write.get("value"), profileId),
                    write.has("manufacturerCode")
                            ? flexibleInt(write, "manufacturerCode", profileId)
                            : 0));
        }
        return result;
    }

    private static List<ConfirmationCharacterization> parseConfirmation(
            JsonNode confirmation, String profileId) {
        if (confirmation == null || confirmation.isNull()) {
            return null;
        }
        List<ConfirmationCharacterization> result = new ArrayList<>();
        for (JsonNode entry : confirmation) {
            Set<DegradeRule> degradeRules = new LinkedHashSet<>();
            JsonNode rules = entry.get("degradeRule");
            if (rules != null && rules.isArray()) {
                for (JsonNode rule : rules) {
                    degradeRules.add(parseDegradeRule(rule.asText(), profileId));
                }
            }
            result.add(new ConfirmationCharacterization(
                    requiredText(entry, "capability", profileId),
                    com.homesynapse.device.ConfirmationMode.valueOf(
                            requiredText(entry, "confirmationMode", profileId)),
                    optionalText(entry, "authoritativeAttribute"),
                    ReportsAuthoritative.valueOf(
                            requiredText(entry, "reportsAuthoritative", profileId)),
                    ReportingPosture.valueOf(
                            requiredText(entry, "reportingPosture", profileId)),
                    Confirmability.valueOf(
                            requiredText(entry, "confirmability", profileId)),
                    entry.path("recommendedTimeoutMs").asLong(0L),
                    degradeRules,
                    optionalText(entry, "notes")));
        }
        return result;
    }

    private static DegradeRule parseDegradeRule(String text, String profileId) {
        for (DegradeRule rule : DegradeRule.values()) {
            if (rule.name().equals(text)) {
                return rule;
            }
        }
        // F-15: an unknown degrade rule fails THIS profile closed at the point the
        // string is parsed (lazy materialization, §F) — dropping or defaulting it
        // would let a future vocabulary addition change confirmation behavior
        // without anyone noticing.
        List<String> supported = new ArrayList<>();
        for (DegradeRule rule : DegradeRule.values()) {
            supported.add(rule.name());
        }
        throw new ProfileLoadException(
                "Profile '" + profileId + "' carries unknown 'degradeRule' value '"
                        + text + "'; supported: " + String.join(", ", supported)
                        + " (unknown rule fails closed, F-15)");
    }

    // ── JSON field helpers ──────────────────────────────────────────────────

    private static String requiredText(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ProfileLoadException(
                    "'" + owner + "' requires a non-blank '" + field + "' field");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Set<String> parseStringSet(JsonNode array) {
        if (array == null || array.isNull()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode element : array) {
            result.add(element.asText());
        }
        return result;
    }

    private static Set<Integer> intSet(JsonNode array, String profileId) {
        if (array == null || !array.isArray()) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' fingerprint endpoint requires "
                            + "inClusters/outClusters arrays");
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (JsonNode element : array) {
            result.add(element.isInt() ? element.asInt()
                    : parseFlexibleInt(element.asText(), profileId, "cluster id"));
        }
        return result;
    }

    private static int flexibleInt(JsonNode node, String field, String profileId) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' requires an integer '" + field
                            + "' field");
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        return parseFlexibleInt(value.asText(), profileId, field);
    }

    private static int parseFlexibleInt(String text, String profileId, String what) {
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Integer.parseInt(trimmed.substring(2), 16);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' " + what + " '" + text
                            + "' is not an integer (decimal or 0x-hex)");
        }
    }

    private static Object scalarValue(JsonNode value, String profileId) {
        if (value == null || value.isNull()) {
            throw new ProfileLoadException(
                    "Profile '" + profileId + "' initialization write requires a "
                            + "'value' field");
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isInt()) {
            return value.asInt();
        }
        if (value.isLong()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        return value.asText();
    }
}

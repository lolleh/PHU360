/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dashboard;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.exception.ReportException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The programme dashboards, read from a JSON definition file.
 * <p>
 * Two files are consulted, in order:
 * <ol>
 * <li>{@code program-indicators.json} in the module's configuration directory, which is where an
 * administrator edits the definitions without rebuilding anything;</li>
 * <li>{@code program-indicators.json} on the classpath, the set this module ships.</li>
 * </ol>
 * A missing configuration file is not an error, it simply means the shipped definitions are in use.
 * A malformed file <em>is</em> an error: it is reported rather than skipped, because silently
 * falling back to the shipped set would show a clinician a plausible dashboard that quietly ignores
 * whatever they changed.
 * <p>
 * The file is parsed with a tree model rather than bound to objects so that each problem can be
 * reported with the path that caused it, which is the difference between an administrator fixing a
 * typo in one minute and an afternoon of guessing.
 */
public class ProgramIndicatorCatalog {
	
	private static final Log log = LogFactory.getLog(ProgramIndicatorCatalog.class);
	
	/** File name looked for in the module configuration directory and on the classpath. */
	public static final String FILE_NAME = "program-indicators.json";
	
	/** Highest definition format this code understands. */
	private static final int SUPPORTED_VERSION = 1;
	
	private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static volatile ProgramIndicatorCatalog cached;
	
	/**
	 * When the cached copy was read, so an edited file can be noticed. Zero means "not from a file we
	 * can watch", which is the case for the bundled definitions and needs no re-reading.
	 */
	private static volatile long cachedFromModified;
	
	/**
	 * Whether the cached copy came from the administrator's own file. Removing that file is an edit
	 * too: without this, deleting it would leave the deleted definitions on screen for ever, which is
	 * the exact failure the timestamp check exists to prevent.
	 */
	private static volatile boolean cachedFromOverrideFile;
	
	/**
	 * Where an administrator's edited copy of the definitions lives, when the web application has told
	 * us. The module's API cannot resolve the webapp's own layout itself, so the page context sets this
	 * once at startup; everything else reads it.
	 */
	private static volatile File configurationDirectory;
	
	private final List<DashboardProgram> programs;
	
	private final String sourceDescription;
	
	private ProgramIndicatorCatalog(List<DashboardProgram> programs, String sourceDescription) {
		this.programs = java.util.Collections.unmodifiableList(programs);
		this.sourceDescription = sourceDescription;
	}
	
	/**
	 * @return every programme the definition file describes, in file order, never null
	 * @throws ReportException when the definition file cannot be read or is not valid
	 */
	public static ProgramIndicatorCatalog load() {
		ProgramIndicatorCatalog current = cached;
		if (current != null && !wasEditedUnderneath()) {
			return current;
		}
		synchronized (ProgramIndicatorCatalog.class) {
			if (cached == null || wasEditedUnderneath()) {
				// Re-reading here rather than failing is what lets an administrator save the definition
				// file, reload the page and see the change. A file that has been made invalid does raise,
				// because showing the previous definitions as though they were the new ones would be a
				// worse lie than an error.
				cached = read();
			}
			return cached;
		}
	}
	
	/**
	 * @return true when the definition file on disk has changed since it was last read
	 */
	private static boolean wasEditedUnderneath() {
		File override = configurationFile();
		if (override == null || !override.isFile()) {
			// The edited file is gone, so the bundled definitions apply again. Report that as an edit
			// only when the cached copy really did come from that file.
			return cachedFromOverrideFile;
		}
		return override.lastModified() != cachedFromModified;
	}
	
	/**
	 * Forgets the parsed definitions, and the directory they were read from. The next {@link #load()}
	 * reads the file again, which is what makes an administrator's edit visible without a server
	 * restart.
	 */
	public static void clearCache() {
		synchronized (ProgramIndicatorCatalog.class) {
			cached = null;
			cachedFromModified = 0;
			cachedFromOverrideFile = false;
			configurationDirectory = null;
		}
	}
	
	/**
	 * Points the catalogue at the directory holding an administrator's edited copy of the definitions.
	 * <p>
	 * Called once by the web application context, because the module's API has no way to work out where
	 * a webapp keeps its module configuration.
	 *
	 * @param directory the directory, or null to look only at the global property and the bundled file
	 */
	public static void setConfigurationDirectory(File directory) {
		synchronized (ProgramIndicatorCatalog.class) {
			configurationDirectory = directory;
			cached = null;
		}
	}
	
	/**
	 * @return every programme the definition file describes, in file order
	 */
	public List<DashboardProgram> getPrograms() {
		return programs;
	}
	
	/**
	 * @param uuid a programme UUID
	 * @return the matching programme, or null when the file does not describe it. Matching ignores case
	 *         and surrounding space, so a UUID pasted from a URL works.
	 */
	public DashboardProgram findByUuid(String uuid) {
		if (uuid == null) {
			return null;
		}
		String wanted = uuid.trim().toLowerCase(Locale.ROOT);
		for (DashboardProgram program : programs) {
			if (program.getUuid().toLowerCase(Locale.ROOT).equals(wanted)) {
				return program;
			}
		}
		return null;
	}
	
	/**
	 * @return where the definitions were read from, shown on the dashboards page so an administrator
	 *         can tell at a glance whether their edit is the one in effect
	 */
	public String getSourceDescription() {
		return sourceDescription;
	}
	
	// ---------------------------------------------------------------- loading
	
	private static ProgramIndicatorCatalog read() {
		File override = configurationFile();
		if (override != null && override.isFile()) {
			try {
				String json = new String(Files.readAllBytes(override.toPath()), StandardCharsets.UTF_8);
				long readAt = override.lastModified();
				ProgramIndicatorCatalog catalogue = parse(json, override.getAbsolutePath());
				// Only recorded once the file has proved it parses, so a broken edit is retried and
				// reported on the next load instead of being remembered as read.
				cachedFromModified = readAt;
				cachedFromOverrideFile = true;
				return catalogue;
			}
			catch (IOException e) {
				throw new ReportException("Could not read " + override.getAbsolutePath(), e);
			}
		}
		
		try (InputStream bundled = ProgramIndicatorCatalog.class.getResourceAsStream("/" + FILE_NAME)) {
			if (bundled == null) {
				throw new ReportException("No programme dashboard definitions were found: neither "
				        + (override == null ? "a module configuration directory" : override.getAbsolutePath())
				        + " nor a bundled " + FILE_NAME + " is present.");
			}
			ProgramIndicatorCatalog catalogue = parse(readAll(bundled), "bundled " + FILE_NAME);
			cachedFromModified = 0;
			cachedFromOverrideFile = false;
			return catalogue;
		}
		catch (IOException e) {
			throw new ReportException("Could not read the bundled " + FILE_NAME, e);
		}
	}
	
	/**
	 * Finds the file an administrator would edit.
	 * <p>
	 * Two places are consulted, in order: the directory the webapp registered, and the path held in the
	 * {@code clinicreporting.programIndicatorDefinitions} global property, which is how a deployment
	 * that keeps its configuration somewhere other than the webapp points at its own copy. A path that
	 * does not exist is ignored rather than reported: the global property is left set on a server that
	 * has since moved the file, and that should not take the dashboards down.
	 *
	 * @return the override file if there is one, otherwise null
	 */
	static File configurationFile() {
		File fromDirectory = fromConfigurationDirectory();
		if (fromDirectory != null) {
			return fromDirectory;
		}
		return fromGlobalProperty();
	}
	
	private static File fromConfigurationDirectory() {
		File directory = configurationDirectory;
		if (directory == null || !directory.isDirectory()) {
			return null;
		}
		File file = new File(directory, FILE_NAME);
		return file.isFile() ? file : null;
	}
	
	private static File fromGlobalProperty() {
		String path;
		try {
			path = Context.getAdministrationService()
			        .getGlobalProperty(ClinicReportingConstants.GP_PROGRAM_INDICATOR_DEFINITIONS);
		}
		catch (RuntimeException e) {
			// No authenticated context: unit tests and module start-up. There is nothing configured.
			return null;
		}
		if (path == null || path.trim().isEmpty()) {
			return null;
		}
		File file = new File(path.trim());
		if (!file.isFile()) {
			log.warn("Global property " + ClinicReportingConstants.GP_PROGRAM_INDICATOR_DEFINITIONS + " points at "
			        + file.getAbsolutePath() + ", which does not exist. Using the bundled programme indicator definitions.");
			return null;
		}
		return file;
	}
	
	private static String readAll(InputStream in) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}
	
	// ---------------------------------------------------------------- parsing
	
	/**
	 * Parses and validates a definition document.
	 *
	 * @param json the document
	 * @param source a description of where it came from, used in error messages
	 * @return the validated catalogue
	 * @throws ReportException when the document is not valid, with the offending path named
	 */
	static ProgramIndicatorCatalog parse(String json, String source) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		}
		catch (IOException e) {
			throw new ReportException(source + " is not valid JSON: " + e.getMessage(), e);
		}
		if (root == null || !root.isObject()) {
			throw new ReportException(source + " must contain a JSON object at the top level");
		}
		
		int version = root.path("version").asInt(SUPPORTED_VERSION);
		if (version > SUPPORTED_VERSION) {
			throw new ReportException(source + " declares format version " + version
			        + ", but this module understands version " + SUPPORTED_VERSION + " at most. Upgrade the module.");
		}
		
		JsonNode programNodes = root.path("programs");
		if (!programNodes.isArray() || programNodes.size() == 0) {
			throw new ReportException(source + " must contain a non-empty \"programs\" array");
		}
		
		List<DashboardProgram> programs = new ArrayList<>();
		Set<String> seenUuids = new LinkedHashSet<>();
		for (int i = 0; i < programNodes.size(); i++) {
			DashboardProgram program = parseProgram(programNodes.get(i), i, source);
			if (!seenUuids.add(program.getUuid().toLowerCase(Locale.ROOT))) {
				throw new ReportException(source + ": programme " + program.getUuid()
				        + " is defined twice. Every programme appears once, so a dashboard has one set of figures.");
			}
			programs.add(program);
		}
		
		log.info("Loaded " + programs.size() + " programme dashboard definitions from " + source);
		return new ProgramIndicatorCatalog(programs, source);
	}
	
	private static DashboardProgram parseProgram(JsonNode node, int index, String source) {
		String path = "programs[" + index + "]";
		if (!node.isObject()) {
			throw new ReportException(source + ": " + path + " must be an object");
		}
		
		String uuid = text(node, "uuid", path, source);
		if (!uuid.matches(UUID_PATTERN)) {
			throw new ReportException(source + ": " + path + ".uuid ('" + uuid
			        + "') is not a UUID. Copy it from the OpenMRS programme: enrolments are found by matching this value.");
		}
		path += " (uuid " + uuid + ")";
		
		String name = text(node, "name", path, source);
		String shortName = optionalText(node, "shortName", path, source);
		String description = optionalText(node, "description", path, source);
		
		JsonNode indicatorNodes = node.path("indicators");
		if (!indicatorNodes.isArray() || indicatorNodes.size() == 0) {
			throw new ReportException(source + ": " + path
			        + ".indicators must be a non-empty array. A programme with no indicators has nothing to show.");
		}
		
		List<DashboardIndicator> indicators = new ArrayList<>();
		Set<String> seenIds = new LinkedHashSet<>();
		for (int j = 0; j < indicatorNodes.size(); j++) {
			DashboardIndicator indicator = parseIndicator(indicatorNodes.get(j), path + ".indicators[" + j + "]", source);
			if (!seenIds.add(indicator.getId())) {
				throw new ReportException(source + ": " + path + " defines indicator '" + indicator.getId()
				        + "' twice. Indicator ids become table ids in the page, so they have to be unique.");
			}
			indicators.add(indicator);
		}
		
		return new DashboardProgram(uuid.toLowerCase(Locale.ROOT), name, shortName, description, indicators);
	}
	
	private static DashboardIndicator parseIndicator(JsonNode node, String path, String source) {
		if (!node.isObject()) {
			throw new ReportException(source + ": " + path + " must be an object");
		}
		
		String id = text(node, "id", path, source);
		if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
			throw new ReportException(source + ": " + path + ".id ('" + id
			        + "') may contain only letters, digits, hyphens and underscores, up to 64 characters.");
		}
		String label = text(node, "label", path, source);
		String description = optionalText(node, "description", path, source);
		
		String metricName = text(node, "metric", path, source);
		IndicatorMetric metric = IndicatorMetric.fromDefinitionName(metricName);
		if (metric == null) {
			throw new ReportException(source + ": " + path + ".metric ('" + metricName + "') on '" + id
			        + "' is not a measurement this module computes. Use one of: " + IndicatorMetric.allDefinitionNames()
			        + ".");
		}
		
		String chartKind = optionalText(node, "chart", path, source);
		if (chartKind == null) {
			chartKind = metric.producesBreakdown() ? "bar" : "none";
		} else if (!DashboardIndicator.isSupportedChartKind(chartKind)) {
			throw new ReportException(source + ": " + path + ".chart ('" + chartKind + "') on '" + id
			        + "' is not a chart this module draws. Use one of: "
			        + String.join(", ", DashboardIndicator.supportedChartKinds()) + ".");
		} else {
			chartKind = chartKind.toLowerCase(Locale.ROOT);
		}
		
		Double target = optionalNumber(node, "target", path, source);
		if (target != null && target < 0) {
			throw new ReportException(source + ": " + path + ".target on '" + id + "' is negative (" + target
			        + "). A target is a figure to reach, so it cannot be below zero.");
		}
		boolean higherIsBetter = node.path("higherIsBetter").asBoolean(true);
		
		ConceptReferences concepts = conceptReferences(node.get("concepts"), path + ".concepts", source);
		ConceptReferences denominators = conceptReferences(node.get("denominatorConcepts"), path + ".denominatorConcepts",
		    source);
		
		if (metric.requiresConcept() && concepts.isEmpty()) {
			throw new ReportException(source + ": " + path + " ('" + id + "') uses measurement '"
			        + metric.getDefinitionName()
			        + "', which needs a concept to count, but names none. Add a \"concepts\" entry with a uuid or a name.");
		}
		if (metric.isRatio() && denominators.isEmpty()) {
			throw new ReportException(source + ": " + path + " ('" + id + "') uses measurement '"
			        + metric.getDefinitionName() + "', which divides by patients tested, but names no "
			        + "\"denominatorConcepts\". A rate without its denominator is not interpretable.");
		}
		if (metric.requiresThreshold() && optionalNumber(node, "valueBelow", path, source) == null) {
			throw new ReportException(source + ": " + path + " ('" + id + "') uses measurement '"
			        + metric.getDefinitionName() + "', which counts only the values under a limit, but names no "
			        + "\"valueBelow\". Without the limit it would count every value, which is not what "
			        + metric.getDefinitionName() + " means.");
		}
		if (!metric.isRatio() && !denominators.isEmpty()) {
			throw new ReportException(
			        source + ": " + path + " ('" + id + "') names \"denominatorConcepts\", but measurement '"
			                + metric.getDefinitionName() + "' has no denominator. Denominators apply to '"
			                + IndicatorMetric.POSITIVE_RATE.getDefinitionName() + "' only.");
		}
		
		return new DashboardIndicator(id, label, description, metric, chartKind, target, higherIsBetter, concepts.asList(),
		        concepts.namesAsList(), denominators.asList(), denominators.namesAsList(),
		        optionalNumber(node, "valueBelow", path, source));
	}
	
	/**
	 * Reads a list of concept references, accepting either {@code {"uuid": ...}} / {@code {"name":
	 * ...}} objects or a bare string, which is taken as a name unless it is shaped like a UUID. The
	 * bare-string form exists because most definitions name concepts rather than carrying a
	 * distribution's UUIDs, and requiring braces around every entry would make the common case tedious.
	 */
	private static ConceptReferences conceptReferences(JsonNode node, String path, String source) {
		ConceptReferences references = new ConceptReferences();
		if (node == null || node.isNull()) {
			return references;
		}
		if (!node.isArray()) {
			throw new ReportException(source + ": " + path + " must be an array of "
			        + "[{\"uuid\": \"...\"}] or [{\"name\": \"...\"}] entries");
		}
		for (int i = 0; i < node.size(); i++) {
			JsonNode entry = node.get(i);
			if (entry.isTextual()) {
				String value = entry.asText().trim();
				if (value.matches(UUID_PATTERN)) {
					references.uuids.add(value.toLowerCase(Locale.ROOT));
				} else {
					references.names.add(value);
				}
				continue;
			}
			if (!entry.isObject()) {
				throw new ReportException(source + ": " + path + "[" + i
				        + "] must be a string concept name or an object with a \"uuid\" or \"name\"");
			}
			String uuid = optionalText(entry, "uuid", path + "[" + i + "]", source);
			String name = optionalText(entry, "name", path + "[" + i + "]", source);
			if (uuid != null) {
				if (!uuid.matches(UUID_PATTERN)) {
					throw new ReportException(source + ": " + path + "[" + i + "].uuid ('" + uuid + "') is not a UUID");
				}
				references.uuids.add(uuid.toLowerCase(Locale.ROOT));
			}
			if (name != null) {
				references.names.add(name);
			}
			if (uuid == null && name == null) {
				throw new ReportException(source + ": " + path + "[" + i + "] has neither a \"uuid\" nor a \"name\"");
			}
		}
		return references;
	}
	
	// ---------------------------------------------------------------- small helpers
	
	private static String text(JsonNode node, String field, String path, String source) {
		String value = optionalText(node, field, path, source);
		if (value == null) {
			throw new ReportException(source + ": " + path + " is missing the required \"" + field + "\"");
		}
		return value;
	}
	
	private static String optionalText(JsonNode node, String field, String path, String source) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new ReportException(source + ": " + path + "." + field + " must be text");
		}
		String trimmed = value.asText().trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
	
	private static Double optionalNumber(JsonNode node, String field, String path, String source) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isNumber()) {
			throw new ReportException(source + ": " + path + "." + field + " must be a number");
		}
		return Double.valueOf(value.asDouble());
	}
	
	private static final class ConceptReferences {
		
		private final Set<String> uuids = new LinkedHashSet<>();
		
		private final Set<String> names = new LinkedHashSet<>();
		
		private List<String> asList() {
			return new ArrayList<>(uuids);
		}
		
		private List<String> namesAsList() {
			return new ArrayList<>(names);
		}
		
		private boolean isEmpty() {
			return uuids.isEmpty() && names.isEmpty();
		}
	}
	
}

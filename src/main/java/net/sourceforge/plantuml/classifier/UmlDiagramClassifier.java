package net.sourceforge.plantuml.classifier;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import net.atmp.CucaDiagram;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.abel.Entity;
import net.sourceforge.plantuml.abel.LeafType;
import net.sourceforge.plantuml.activitydiagram3.ActivityDiagram3;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.decoration.symbol.USymbol;
import net.sourceforge.plantuml.decoration.symbol.USymbols;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.sequencediagram.SequenceDiagram;
import net.sourceforge.plantuml.skin.UmlDiagramType;
import net.sourceforge.plantuml.timingdiagram.TimingDiagram;

public class UmlDiagramClassifier {

	private static boolean diagnostics = false;

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: UmlDiagramClassifier [--diagnostics] <file.puml> [file2.puml ...]");
			System.err.println("       UmlDiagramClassifier [--diagnostics] --dir <directory>");
			System.exit(1);
		}

		int argStart = 0;
		if ("--diagnostics".equals(args[0])) {
			diagnostics = true;
			argStart = 1;
		}

		if (argStart >= args.length) {
			System.err.println("No files specified");
			System.exit(1);
		}

		if ("--dir".equals(args[argStart]) && args.length >= argStart + 2) {
			final File dir = new File(args[argStart + 1]);
			if (!dir.isDirectory()) {
				System.err.println("Not a directory: " + args[argStart + 1]);
				System.exit(1);
			}
			final File[] files = dir.listFiles((d, name) ->
				name.endsWith(".puml") || name.endsWith(".plantuml") || name.endsWith(".pu")
				|| name.endsWith(".wsd") || name.endsWith(".uml") || name.endsWith(".iuml")
			);
			if (files != null) {
				for (final File f : files) {
					processFile(f.getAbsolutePath());
				}
			}
		} else {
			for (int i = argStart; i < args.length; i++) {
				processFile(args[i]);
			}
		}
	}

	private static void processFile(String path) {
		try {
			final String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
			final SourceStringReader reader = new SourceStringReader(source);

			int blockIndex = 0;
			for (final BlockUml block : reader.getBlocks()) {
				final Diagram diagram = block.getDiagram();
				final String fileId = new File(path).getName();
				final String id = blockIndex == 0 ? fileId : fileId + "_" + blockIndex;

				if (diagram instanceof PSystemError) {
					printResult(id, null, "parse_error", null);
				} else {
					final Map<String, Object> diag = diagnostics ? new LinkedHashMap<>() : null;
					final String type = classify(diagram, diag);
					printResult(id, type, null, diag);
				}
				blockIndex++;
			}
		} catch (IOException e) {
			printResult(new File(path).getName(), null, "io_error:" + e.getMessage(), null);
		} catch (Exception e) {
			printResult(new File(path).getName(), null, "error:" + e.getMessage(), null);
		}
	}

	private static String classify(Diagram diagram, Map<String, Object> diag) {
		if (diagram instanceof SequenceDiagram)
			return "sequence";

		if (diagram instanceof ActivityDiagram3)
			return "activity";

		if (diagram instanceof TimingDiagram)
			return "timing";

		if (diagram instanceof CucaDiagram) {
			final CucaDiagram cuca = (CucaDiagram) diagram;
			final UmlDiagramType umlType = cuca.getUmlDiagramType();

			if (diag != null)
				diag.put("uml_diagram_type", umlType.name());

			if (umlType == UmlDiagramType.STATE)
				return "state";

			if (umlType == UmlDiagramType.CLASS)
				return classifyClassOrObject(cuca);

			if (umlType == UmlDiagramType.DESCRIPTION)
				return classifyDescription(cuca, diag);

			if (umlType == UmlDiagramType.ACTIVITY)
				return "activity";

			return umlType.name().toLowerCase();
		}

		return "unsupported:" + diagram.getClass().getSimpleName();
	}

	// --- Class vs Object ---

	private static String classifyClassOrObject(CucaDiagram diagram) {
		boolean hasObject = false;
		boolean hasClassLike = false;

		for (final Entity entity : diagram.leafs()) {
			final LeafType lt = entity.getLeafType();
			if (lt == null)
				continue;
			if (lt == LeafType.OBJECT || lt == LeafType.MAP)
				hasObject = true;
			else if (lt.isLikeClass())
				hasClassLike = true;
		}

		if (hasObject && !hasClassLike)
			return "object";

		return "class";
	}

	// --- Component vs Deployment vs Use Case ---

	private static String classifyDescription(CucaDiagram diagram, Map<String, Object> diag) {
		boolean hasUsecase = false;
		boolean hasActor = false;
		boolean hasComponent = false;
		boolean hasDeployment = false;

		// Diagnostics: count each symbol and leaf type
		final Map<String, Integer> symbolCounts = diag != null ? new LinkedHashMap<>() : null;
		final Map<String, Integer> leafTypeCounts = diag != null ? new LinkedHashMap<>() : null;
		int nullSymbolCount = 0;
		int totalEntities = 0;

		for (final Entity entity : diagram.leafs()) {
			final LeafType lt = entity.getLeafType();
			final USymbol sym = entity.getUSymbol();
			totalEntities++;

			if (diag != null) {
				final String ltName = lt != null ? lt.name() : "null";
				leafTypeCounts.merge(ltName, 1, Integer::sum);

				if (sym != null) {
					symbolCounts.merge(symbolName(sym), 1, Integer::sum);
				} else {
					nullSymbolCount++;
				}
			}

			if (lt == LeafType.USECASE || lt == LeafType.USECASE_BUSINESS) {
				hasUsecase = true;
				continue;
			}

			if (sym == null)
				continue;

			if (isActorSymbol(sym))
				hasActor = true;
			else if (isComponentSymbol(sym))
				hasComponent = true;
			else if (isDeploymentSymbol(sym))
				hasDeployment = true;
		}

		final String result;
		final String rule;

		if (hasUsecase || (hasActor && !hasComponent && !hasDeployment)) {
			result = "usecase";
			rule = hasUsecase ? "has_usecase_leaf" : "actor_only";
		} else if (hasDeployment && !hasComponent) {
			result = "deployment";
			rule = "deployment_no_component";
		} else if (hasComponent && !hasDeployment) {
			result = "component";
			rule = "component_no_deployment";
		} else if (hasComponent && hasDeployment) {
			result = "deployment";
			rule = "both_deployment_wins";
		} else {
			result = "component";
			rule = "default_no_symbols";
		}

		if (diag != null) {
			diag.put("total_entities", totalEntities);
			diag.put("leaf_types", leafTypeCounts);
			diag.put("symbols", symbolCounts);
			diag.put("null_symbol_count", nullSymbolCount);
			diag.put("has_usecase", hasUsecase);
			diag.put("has_actor", hasActor);
			diag.put("has_component", hasComponent);
			diag.put("has_deployment", hasDeployment);
			diag.put("rule", rule);
		}

		return result;
	}

	private static String symbolName(USymbol sym) {
		if (sym == USymbols.NODE) return "NODE";
		if (sym == USymbols.CLOUD) return "CLOUD";
		if (sym == USymbols.DATABASE) return "DATABASE";
		if (sym == USymbols.ARTIFACT) return "ARTIFACT";
		if (sym == USymbols.STORAGE) return "STORAGE";
		if (sym == USymbols.FOLDER) return "FOLDER";
		if (sym == USymbols.FRAME) return "FRAME";
		if (sym == USymbols.COMPONENT1) return "COMPONENT1";
		if (sym == USymbols.COMPONENT2) return "COMPONENT2";
		if (sym == USymbols.COMPONENT_RECTANGLE) return "COMPONENT_RECTANGLE";
		if (sym == USymbols.ACTOR_STICKMAN) return "ACTOR_STICKMAN";
		if (sym == USymbols.ACTOR_AWESOME) return "ACTOR_AWESOME";
		if (sym == USymbols.ACTOR_HOLLOW) return "ACTOR_HOLLOW";
		if (sym == USymbols.ACTOR_STICKMAN_BUSINESS) return "ACTOR_STICKMAN_BUSINESS";
		if (sym == USymbols.INTERFACE) return "INTERFACE";
		if (sym == USymbols.PACKAGE) return "PACKAGE";
		if (sym == USymbols.RECTANGLE) return "RECTANGLE";
		if (sym == USymbols.CARD) return "CARD";
		if (sym == USymbols.QUEUE) return "QUEUE";
		if (sym == USymbols.STACK) return "STACK";
		if (sym == USymbols.AGENT) return "AGENT";
		if (sym == USymbols.BOUNDARY) return "BOUNDARY";
		if (sym == USymbols.CONTROL) return "CONTROL";
		if (sym == USymbols.ENTITY_DOMAIN) return "ENTITY_DOMAIN";
		if (sym == USymbols.COLLECTIONS) return "COLLECTIONS";
		if (sym == USymbols.HEXAGON) return "HEXAGON";
		if (sym == USymbols.PERSON) return "PERSON";
		if (sym == USymbols.LABEL) return "LABEL";
		if (sym == USymbols.ARCHIMATE) return "ARCHIMATE";
		if (sym == USymbols.ACTION) return "ACTION";
		if (sym == USymbols.FILE) return "FILE";
		if (sym == USymbols.USECASE) return "USECASE";
		if (sym == USymbols.USECASE_BUSINESS) return "USECASE_BUSINESS";
		if (sym == USymbols.PROCESS) return "PROCESS";
		if (sym == USymbols.GROUP) return "GROUP";
		if (sym == USymbols.PARTITION) return "PARTITION";
		return sym.getClass().getSimpleName();
	}

	private static boolean isActorSymbol(USymbol sym) {
		return sym == USymbols.ACTOR_STICKMAN
			|| sym == USymbols.ACTOR_AWESOME
			|| sym == USymbols.ACTOR_HOLLOW
			|| sym == USymbols.ACTOR_STICKMAN_BUSINESS;
	}

	private static boolean isComponentSymbol(USymbol sym) {
		return sym == USymbols.COMPONENT1
			|| sym == USymbols.COMPONENT2
			|| sym == USymbols.COMPONENT_RECTANGLE
			|| sym == USymbols.INTERFACE;
	}

	private static boolean isDeploymentSymbol(USymbol sym) {
		return sym == USymbols.ARTIFACT
			|| sym == USymbols.STORAGE
			|| sym == USymbols.FILE
			|| sym == USymbols.STACK;
	}

	// --- JSON output ---

	private static void printResult(String id, String diagramType, String error,
			Map<String, Object> diag) {
		final StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append("\"file\":").append(jsonString(id));
		sb.append(",\"diagram_type\":").append(jsonString(diagramType));
		sb.append(",\"error\":").append(jsonString(error));
		if (diag != null && !diag.isEmpty()) {
			sb.append(",\"diagnostics\":{");
			boolean first = true;
			for (final Map.Entry<String, Object> e : diag.entrySet()) {
				if (!first) sb.append(",");
				first = false;
				sb.append(jsonString(e.getKey())).append(":");
				sb.append(jsonValue(e.getValue()));
			}
			sb.append("}");
		}
		sb.append("}");
		System.out.println(sb.toString());
	}

	@SuppressWarnings("unchecked")
	private static String jsonValue(Object v) {
		if (v == null) return "null";
		if (v instanceof String) return jsonString((String) v);
		if (v instanceof Boolean || v instanceof Number) return v.toString();
		if (v instanceof Map) {
			final Map<String, Object> map = (Map<String, Object>) v;
			final StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (final Map.Entry<String, Object> e : map.entrySet()) {
				if (!first) sb.append(",");
				first = false;
				sb.append(jsonString(e.getKey())).append(":").append(jsonValue(e.getValue()));
			}
			sb.append("}");
			return sb.toString();
		}
		return jsonString(v.toString());
	}

	private static String jsonString(String s) {
		if (s == null)
			return "null";
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
	}
}

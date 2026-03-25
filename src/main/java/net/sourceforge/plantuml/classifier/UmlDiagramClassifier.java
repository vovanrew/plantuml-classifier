package net.sourceforge.plantuml.classifier;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: UmlDiagramClassifier <file.puml> [file2.puml ...]");
			System.err.println("       UmlDiagramClassifier --dir <directory>");
			System.exit(1);
		}

		if ("--dir".equals(args[0]) && args.length >= 2) {
			final File dir = new File(args[1]);
			if (!dir.isDirectory()) {
				System.err.println("Not a directory: " + args[1]);
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
			for (final String path : args) {
				processFile(path);
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
					printResult(id, null, "parse_error");
				} else {
					final String type = classify(diagram);
					printResult(id, type, null);
				}
				blockIndex++;
			}
		} catch (IOException e) {
			printResult(new File(path).getName(), null, "io_error:" + e.getMessage());
		} catch (Exception e) {
			printResult(new File(path).getName(), null, "error:" + e.getMessage());
		}
	}

	private static String classify(Diagram diagram) {
		if (diagram instanceof SequenceDiagram)
			return "sequence";

		if (diagram instanceof ActivityDiagram3)
			return "activity";

		if (diagram instanceof TimingDiagram)
			return "timing";

		if (diagram instanceof CucaDiagram) {
			final CucaDiagram cuca = (CucaDiagram) diagram;
			final UmlDiagramType umlType = cuca.getUmlDiagramType();

			if (umlType == UmlDiagramType.STATE)
				return "state";

			if (umlType == UmlDiagramType.CLASS)
				return classifyClassOrObject(cuca);

			if (umlType == UmlDiagramType.DESCRIPTION)
				return classifyDescription(cuca);

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

	private static String classifyDescription(CucaDiagram diagram) {
		boolean hasUsecase = false;
		boolean hasActor = false;
		boolean hasComponent = false;
		boolean hasDeployment = false;

		for (final Entity entity : diagram.leafs()) {
			final LeafType lt = entity.getLeafType();
			final USymbol sym = entity.getUSymbol();

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

		if (hasUsecase || (hasActor && !hasComponent && !hasDeployment))
			return "usecase";

		if (hasDeployment && !hasComponent)
			return "deployment";

		if (hasComponent && !hasDeployment)
			return "component";

		if (hasComponent && hasDeployment)
			return "deployment";

		return "component";
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
			|| sym == USymbols.COMPONENT_RECTANGLE;
	}

	private static boolean isDeploymentSymbol(USymbol sym) {
		return sym == USymbols.NODE
			|| sym == USymbols.CLOUD
			|| sym == USymbols.DATABASE
			|| sym == USymbols.ARTIFACT
			|| sym == USymbols.STORAGE
			|| sym == USymbols.FOLDER
			|| sym == USymbols.FRAME;
	}

	// --- JSON output ---

	private static void printResult(String id, String diagramType, String error) {
		final StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append("\"file\":").append(jsonString(id));
		sb.append(",\"diagram_type\":").append(jsonString(diagramType));
		sb.append(",\"error\":").append(jsonString(error));
		sb.append("}");
		System.out.println(sb.toString());
	}

	private static String jsonString(String s) {
		if (s == null)
			return "null";
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
	}
}

package dev.voxelbrew.cli;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.api.model.*;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.sketch.SketchProfiles;
import dev.voxelbrew.importer.DocumentSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "voxelbrew",
        description = "VoxelBrew parametric CAD; all dimensions are millimeters.",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            VoxelBrew.Create.class,
            VoxelBrew.Rectangle.class,
            VoxelBrew.Circle.class,
            VoxelBrew.Extrude.class,
            VoxelBrew.Cut.class,
            VoxelBrew.Edit.class,
            VoxelBrew.Cursor.class,
            VoxelBrew.Repair.class,
            VoxelBrew.History.class,
            VoxelBrew.Inspect.class,
            BodyStyleCommand.class,
            VoxelBrew.NativeDemo.class
        })
@lombok.extern.slf4j.Slf4j
public final class VoxelBrew implements Runnable {

    @Spec
    private CommandSpec spec;

    @Command(name = "exporters", mixinStandardHelpOptions = true, description = "List built-in and external exporters")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Exporters implements Callable<Integer> {
        private final java.util.function.Supplier<dev.voxelbrew.api.exporter.ExporterProviderRegistry> exporters;

        @Option(names = "--plugin-dir")
        Path pluginDirectory = dev.voxelbrew.util.services.Services.load(
                        dev.voxelbrew.api.userconfig.UserConfigService.class)
                .pluginsFolder();

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = exporters.get().withDirectory(pluginDirectory)) {
                String appVersion = dev.voxelbrew.util.update.BuildVersion.current();
                registry.descriptors()
                        .forEach(descriptor -> spec.commandLine()
                                .getOut()
                                .println(descriptor.id() + "\t" + descriptor.name()
                                        + (descriptor.version().equals(appVersion)
                                                ? ""
                                                : " (" + descriptor.version() + ")")));
            }
            return 0;
        }
    }

    @Command(
            name = "export",
            mixinStandardHelpOptions = true,
            description = "Export a complete document using a registered provider")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Export implements Callable<Integer> {
        private final DocumentSource documents;
        private final java.util.function.Supplier<dev.voxelbrew.api.exporter.ExporterProviderRegistry> exporters;

        @Parameters(index = "0", arity = "0..1")
        Path file;

        @Option(names = "--format", required = true)
        String format;

        @Option(names = "--output", description = "Destination for exporters that write a local file")
        Path output;

        @Option(names = "--body")
        String body;

        @Option(names = "--instance", description = "Selected build instance ID; requires BUILD_PLATE space")
        String instance;

        @Option(names = "--plate", description = "Select a slicer plate for build-space or stored-job export")
        String plate;

        @Option(
                names = "--space",
                defaultValue = "DESIGN",
                description = "DESIGN or BUILD_PLATE; all coordinates are millimeters")
        String space;

        @Option(
                names = "--selection",
                defaultValue = "MODEL",
                description = "MODEL (default), SUPPORTS or ALL; support solids require BUILD_PLATE")
        String contents;

        @Option(names = "--plugin-dir")
        Path pluginDirectory = dev.voxelbrew.util.services.Services.load(
                        dev.voxelbrew.api.userconfig.UserConfigService.class)
                .pluginsFolder();

        @Option(names = "--chord-tolerance", defaultValue = "0.02", description = "Absolute chordal tolerance (mm)")
        double chord;

        @Option(names = "--angular-tolerance", defaultValue = "5", description = "Angular tolerance (degrees)")
        double angle;

        @Option(names = "--option", description = "Provider option KEY=VALUE; repeatable")
        Map<String, String> options = new java.util.LinkedHashMap<>();

        @Option(names = "--overwrite")
        boolean overwrite;

        @Option(names = "--export-no-prompt", description = "Fail with all missing export inputs instead of prompting")
        boolean noPrompt;

        @CommandLine.Unmatched
        String[] exportInputs = {};

        @Spec
        CommandSpec spec;

        private java.util.Map<String, String> suppliedOptions(CommandLine.ParseResult matched) {
            var supplied = new java.util.LinkedHashMap<String, String>();
            if (matched.hasMatchedOption("--output")) supplied.put("destination", output.toString());
            if (matched.hasMatchedOption("--overwrite")) supplied.put("overwrite", Boolean.toString(overwrite));
            if (matched.hasMatchedOption("--space")) supplied.put("space", space);
            if (matched.hasMatchedOption("--selection")) supplied.put("selection", contents);
            if (matched.hasMatchedOption("--body")) supplied.put("body", body);
            if (matched.hasMatchedOption("--instance")) supplied.put("instance", instance);
            if (matched.hasMatchedOption("--chord-tolerance")) {
                supplied.put("chord-tolerance", Double.toString(chord));
            }
            if (matched.hasMatchedOption("--angular-tolerance")) {
                supplied.put("angular-tolerance", Double.toString(angle));
            }
            options.forEach((key, value) -> supplied.put("option." + key, value));
            return supplied;
        }

        @Override
        public Integer call() throws Exception {
            if (!java.util.List.of("DESIGN", "BUILD_PLATE").contains(space)) {
                throw new IllegalArgumentException("Invalid --space: " + space);
            }
            if (!java.util.List.of("MODEL", "SUPPORTS", "ALL").contains(contents)) {
                throw new IllegalArgumentException("Invalid --selection: " + contents);
            }
            var document = documents.load(file);
            if (plate != null) document = WorkspaceCommands.selectPlate(document, plate);
            final var selectedDocument = document;
            try (var registry = exporters.get().withDirectory(pluginDirectory)) {
                var provider = registry.require(format);
                var matched = spec.commandLine().getParseResult();
                var supplied = suppliedOptions(matched);
                boolean meshFlagsProvided = matched.hasMatchedOption("--body")
                        || matched.hasMatchedOption("--instance")
                        || matched.hasMatchedOption("--space")
                        || matched.hasMatchedOption("--selection")
                        || matched.hasMatchedOption("--option");
                var parent = new CliExportWindow(
                        provider.descriptor().id(),
                        pluginDirectory,
                        supplied,
                        exportInputs,
                        spec.commandLine().getOut(),
                        spec.commandLine().getErr(),
                        new java.io.BufferedReader(
                                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)),
                        !noPrompt && (System.console() != null || System.in.available() > 0),
                        noPrompt,
                        meshFlagsProvided);
                try {
                    if (provider.create().export(selectedDocument, parent).isEmpty()) {
                        log.info("Export {} cancelled", format);
                        return 1;
                    }
                } catch (java.util.concurrent.CancellationException cancelled) {
                    log.info("Export {} cancelled", format);
                    return 1;
                } catch (Exception | LinkageError failure) {
                    log.error("Export {} failed", format, failure);
                    throw failure;
                }
                log.info("Export {} succeeded", format);
                spec.commandLine()
                        .getOut()
                        .println("Exported " + format
                                + (parent.values().containsKey("destination")
                                        ? " to "
                                                + Path.of(parent.values().get("destination"))
                                                        .toAbsolutePath()
                                        : ""));
            }
            return 0;
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    abstract static class FileCommand implements Callable<Integer> {
        final DocumentSource documents;
        final DocumentSave documentWriter;

        @Parameters(index = "0", arity = "0..1", description = "Editable .vxb file (or --document)")
        Path file;

        @Option(names = "--revision", description = "Expected document fingerprint from designer inspect")
        String expectedRevision;

        @Option(names = "--plugin-dir", description = "Trusted feature and service JAR directory")
        Path pluginDirectory = dev.voxelbrew.util.services.Services.load(
                        dev.voxelbrew.api.userconfig.UserConfigService.class)
                .pluginsFolder();

        @Spec
        CommandSpec spec;

        ModelSession session() throws java.io.IOException {
            var document = documents.load(file);
            if (expectedRevision != null
                    && !expectedRevision.equals(dev.voxelbrew.engine.persistence.DocumentCodec.fingerprint(document))) {
                throw new IllegalArgumentException("Document changed; inspect and select topology again");
            }
            return new ModelSession(document);
        }

        int save(ModelSession session) throws java.io.IOException {
            try (Evaluation evaluation = session.evaluate(pluginDirectory)) {
                if (!evaluation.valid()) {
                    evaluation.diagnostics().stream()
                            .filter(d -> d.state() == Evaluation.State.FAILED || d.state() == Evaluation.State.BLOCKED)
                            .forEach(d -> spec.commandLine()
                                    .getErr()
                                    .println(d.featureId() + " " + d.state() + " " + d.message()));
                    return 1;
                }
            }
            documentWriter.save(session.document(), file, true);
            return 0;
        }

        Feature.Sketch sourceSketch(ModelSession session, String sketchId) {
            return session.document().features().stream()
                    .filter(f -> f.id().equals(sketchId) && f instanceof Feature.Sketch)
                    .map(f -> (Feature.Sketch) f)
                    .findFirst()
                    .orElseThrow(() -> new ModelException(sketchId, "sketch does not exist"));
        }

        Feature.ProfileRef profileRef(ModelSession session, String sketchId) {
            var source = sourceSketch(session, sketchId);
            return new Feature.ProfileRef(source.id(), source.profileId());
        }
    }

    abstract static class AddFeature extends FileCommand {

        @Option(names = "--id", description = "Stable feature ID (default: generated UUID)")
        String id;

        AddFeature(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        ModelSession session() throws java.io.IOException {
            var session = super.session();
            if (id == null) {
                id = Long.toString(session.document().uuidGenerator().next());
            }
            return session;
        }
    }

    abstract static class AddSketch extends AddFeature {

        @Option(names = "--plane", defaultValue = "XY", description = "Origin plane: XY, XZ or YZ")
        String orientation;

        @Option(names = "--face-body", description = "Body ID supporting the sketch plane")
        String faceBody;

        @Option(names = "--face", description = "Planar face index from topology inspection")
        Integer face;

        @Option(names = "--construction-plane", description = "Earlier construction plane feature ID")
        String constructionPlane;

        @Option(names = "--offset-z", defaultValue = "0", description = "XY plane offset, mm")
        double z;

        @Option(
                names = "--plane-id",
                description = "Stable plane ID (default: origin-xy or a sketch-specific offset plane)")
        String planeId;

        @Option(names = "--profile-id", description = "Stable profile ID (default: <feature-id>.profile)")
        String profileId;

        AddSketch(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        private String sketchPlaneId() {
            if (planeId != null) return planeId;
            return z == 0 ? "origin-" + orientation.toLowerCase(Locale.ROOT) : id + ".plane";
        }

        int sketch(Feature.Profile profile) throws java.io.IOException {
            var session = session();
            var plane = new Feature.Plane(
                    sketchPlaneId(),
                    z,
                    Feature.Plane.origin(orientation.toUpperCase(Locale.ROOT)).frame(),
                    null);
            if (constructionPlane != null) {
                if (faceBody != null || face != null) {
                    throw new IllegalArgumentException("Choose a face or a construction plane");
                }
                plane = new Feature.Plane(id + ".plane", z, Transform.IDENTITY, null, constructionPlane);
            }
            if (faceBody != null || face != null) {
                if (faceBody == null || face == null || expectedRevision == null) {
                    throw new IllegalArgumentException("Face attachment requires --face-body, --face and --revision");
                }
                try (var evaluation = session.evaluate(pluginDirectory)) {
                    var body = evaluation.requireCompleteDocument().stream()
                            .filter(b -> b.id().equals(faceBody))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown body " + faceBody));
                    plane = FaceSupport.attach(
                                    body.output(),
                                    body.solid().planarFaces(),
                                    face,
                                    session.document().uuidGenerator())
                            .withOffset(z);
                }
            }
            session.add(new Feature.Sketch(id, profileId == null ? id + ".profile" : profileId, plane, profile));
            return save(session);
        }
    }

    @Command(name = "new", description = "Create an empty editable document.", mixinStandardHelpOptions = true)
    static final class Create extends FileCommand {

        @Option(names = "--overwrite", description = "Explicitly replace an existing supported document")
        boolean overwrite;

        Create(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            documentWriter.save(CadDocument.empty(), file, overwrite);
            return 0;
        }
    }

    @Command(
            name = "rectangle",
            description = "Add a dimensioned rectangle sketch on XY or an offset plane.",
            mixinStandardHelpOptions = true)
    static final class Rectangle extends AddSketch {

        @Option(names = "--x", defaultValue = "0")
        double x;

        @Option(names = "--y", defaultValue = "0")
        double y;

        @Option(names = "--width", required = true)
        double width;

        @Option(names = "--height", required = true)
        double height;

        Rectangle(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            return sketch(new Feature.Rectangle(x, y, width, height));
        }
    }

    @Command(name = "circle", description = "Add a circle by center and diameter, mm.", mixinStandardHelpOptions = true)
    static final class Circle extends AddSketch {

        @Option(names = "--center-x", required = true)
        double x;

        @Option(names = "--center-y", required = true)
        double y;

        @Option(names = "--diameter", required = true)
        double diameter;

        Circle(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            return sketch(new Feature.Circle(x, y, diameter));
        }
    }

    abstract static class ProfileOperation extends AddFeature {
        @Option(names = "--sketch", required = true)
        String sketch;

        @Option(names = "--region", description = "Profile ID from designer sketch regions. Repeat to combine regions.")
        java.util.List<String> regions = java.util.List.of();

        @Option(names = "--include-enclosed", description = "Fill nested faces within the selected regions.")
        boolean includeEnclosed;

        @Option(names = "--body-feature", description = "Target feature ID. Repeat for each cut target.")
        java.util.List<String> bodies = java.util.List.of();

        @Option(names = "--body-output", description = "Output name for all targets, or one per target in order.")
        java.util.List<String> outputs = java.util.List.of();

        @Option(names = "--replace", description = "Replace the existing operation identified by --id in history.")
        boolean replace;

        ProfileOperation(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        Feature.ProfileRef selectedProfile(ModelSession session) {
            var profile = profileRef(session, sketch);
            if (!includeEnclosed && regions.isEmpty()) return profile;
            if (!includeEnclosed && regions.size() == 1) return new Feature.ProfileRef(sketch, regions.getFirst());
            String prefix = profile.profileId() + "/region/";
            var ids = regions.stream()
                    .map(region -> {
                        if (!region.startsWith(prefix)) {
                            throw new IllegalArgumentException("Choose individual regions from " + sketch);
                        }
                        return region.substring(prefix.length());
                    })
                    .distinct()
                    .toList();
            if (includeEnclosed) {
                var resolved = SketchCommands.resolvedSketch(session.document(), sourceSketch(session, sketch));
                if (!(resolved.profile() instanceof Feature.Constrained constrained) || ids.isEmpty()) {
                    throw new IllegalArgumentException("--include-enclosed requires selected sketch regions");
                }
                ids = java.util.List.copyOf(SketchProfiles.includingEnclosedRegions(constrained.geometry(), ids));
            }
            return new Feature.ProfileRef(sketch, SketchProfiles.selectedProfileId(profile.profileId(), ids));
        }

        java.util.List<Feature.BodyRef> targets() {
            if ((!outputs.isEmpty() && bodies.isEmpty()) || (outputs.size() > 1 && outputs.size() != bodies.size())) {
                throw new IllegalArgumentException("Provide one output name or one for each target body");
            }
            var refs = new java.util.ArrayList<Feature.BodyRef>();
            var names = outputs.isEmpty() ? java.util.List.of("body") : outputs;
            for (int i = 0; i < bodies.size(); i++) {
                refs.add(new Feature.BodyRef(bodies.get(i), names.get(names.size() == 1 ? 0 : i)));
            }
            return refs;
        }

        void apply(ModelSession session, Feature feature) {
            if (replace) {
                var original = session.document().features().stream()
                        .filter(f -> f.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new ModelException(id, "Specify an existing feature with --id"));
                if (CutTargets.through(feature)
                        ? !CutTargets.through(original)
                        : !(original instanceof Feature.Extrude || BodyExtrusion.modifiesBody(original))) {
                    throw new ModelException(id, "Existing feature has a different operation type");
                }
                session.replace(feature);
            } else {
                session.add(feature);
            }
        }
    }

    @Command(
            name = "extrude",
            description = "Extrude selected regions by a signed distance.",
            mixinStandardHelpOptions = true)
    static final class Extrude extends ProfileOperation {
        @Option(names = "--distance", required = true)
        String distance;

        @Option(names = "--operation", defaultValue = "NEW_BODY", description = "new_body, join, or cut (blind cut)")
        BodyExtrusion.Operation operation;

        @Option(
                names = "--cut-inward",
                description =
                        "On a supporting face, infer a blind cut for negative distance and a new body for positive distance.")
        boolean cutInward;

        Extrude(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            var refs = targets();
            if (operation == BodyExtrusion.Operation.NEW_BODY && !refs.isEmpty()) {
                throw new IllegalArgumentException("New body extrusions cannot have targets");
            }
            var extrusion = new Feature.Extrude(
                    id,
                    selectedProfile(session),
                    session.document()
                            .parameters()
                            .value(distance, dev.voxelbrew.api.model.parameters.ParameterUnit.MM));
            var selectedOperation = operation;
            if (cutInward) {
                var plane = sourceSketch(session, sketch).plane();
                if (plane.support() == null
                        || plane.offsetZ() != 0
                        || !refs.isEmpty()
                        || spec.commandLine().getParseResult().hasMatchedOption("--operation")) {
                    throw new IllegalArgumentException(
                            "--cut-inward requires a face-supported sketch without offset or explicit operation/targets");
                }
                if (extrusion.distance() < 0) {
                    selectedOperation = BodyExtrusion.Operation.CUT;
                    var before = new ModelSession(session.document());
                    if (replace) {
                        var features = session.document().features();
                        before.setCursor(java.util.stream.IntStream.range(0, features.size())
                                .filter(i -> features.get(i).id().equals(id))
                                .findFirst()
                                .orElseThrow());
                    }
                    try (var evaluation = before.evaluate(pluginDirectory)) {
                        String host = evaluation.bodyIds().get(plane.support().body());
                        refs = java.util.List.of(evaluation.bodies().stream()
                                .filter(body -> body.id().equals(host))
                                .findFirst()
                                .orElseThrow(() -> new ModelException(sketch, "Supporting body is unavailable"))
                                .output());
                    }
                }
            }
            apply(
                    session,
                    new BodyExtrusion(
                                    extrusion,
                                    selectedOperation,
                                    refs.isEmpty() ? null : refs.getFirst(),
                                    refs.stream().skip(1).toList())
                            .feature());
            session.bindExpression(id, "distance", distance);
            return save(session);
        }
    }

    @Command(
            name = "cut",
            description = "Cut selected sketch regions through one or more target bodies.",
            mixinStandardHelpOptions = true)
    static final class Cut extends ProfileOperation {
        Cut(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            apply(session, new CutTargets(targets()).throughFeature(id, selectedProfile(session)));
            return save(session);
        }
    }

    @Command(
            name = "edit",
            description = "Change one dimension as a single engine transaction.",
            mixinStandardHelpOptions = true)
    static final class Edit extends FileCommand {

        @Option(names = "--feature", required = true)
        String feature;

        @Option(
                names = "--field",
                required = true,
                description = "width, height, x, y, centerX, centerY, diameter, offsetZ, distance")
        String field;

        @Option(names = "--value")
        Double value;

        @Option(names = "--expression", description = "Formula or named parameter (instead of --value)")
        String expression;

        Edit(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            if ((value == null) == (expression == null)) {
                throw new IllegalArgumentException("Choose exactly one of --value or --expression");
            }
            if (expression != null) session.bindExpression(feature, field, expression);
            else {
                session.editDimension(feature, field, value, pluginDirectory);
            }
            return save(session);
        }
    }

    @Command(
            name = "cursor",
            description = "Set evaluated feature count without changing the feature list.",
            mixinStandardHelpOptions = true)
    static final class Cursor extends FileCommand {

        @Option(
                names = "--position",
                required = true,
                description = "Number of features to evaluate (0 through history size)")
        int position;

        Cursor(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            session.setCursor(position);
            return save(session);
        }
    }

    @Command(
            name = "repair",
            description = "Repair an extrusion/cut sketch reference or cut body input, preserving IDs.",
            mixinStandardHelpOptions = true)
    static final class Repair extends FileCommand {

        @Option(names = "--feature", required = true)
        String feature;

        @Option(names = "--sketch")
        String sketch;

        @Option(names = "--body-feature")
        String body;

        @Option(names = "--body-output", description = "Named output: body, negative or positive")
        String bodyOutput;

        Repair(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        private Feature.BodyRef repairBody(Feature.BodyRef previous) {
            return new Feature.BodyRef(
                    body == null ? previous.featureId() : body,
                    bodyOutput == null ? previous.outputName() : bodyOutput);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            Feature original = session.document().features().stream()
                    .filter(f -> f.id().equals(feature))
                    .findFirst()
                    .orElseThrow(() -> new ModelException(feature, "feature does not exist"));
            if (original instanceof Feature.Extrude e && sketch != null && body == null) {
                session.replace(new Feature.Extrude(e.id(), profileRef(session, sketch), e.distance()));
            } else if (CutTargets.through(original) && (sketch != null || body != null || bodyOutput != null)) {
                var c = CutTargets.firstThroughCut(original);
                var inputs = new java.util.ArrayList<>(
                        CutTargets.throughTargets(original).bodies());
                inputs.set(0, repairBody(c.body()));
                session.replace(new CutTargets(inputs)
                        .throughFeature(c.id(), sketch == null ? c.profile() : profileRef(session, sketch)));
            } else if (original instanceof Feature.SplitBody s && (body != null || bodyOutput != null)) {
                session.replace(new Feature.SplitBody(s.id(), repairBody(s.body()), s.plane()));
            } else {
                throw new ModelException(feature, "specify a supported --sketch or --body-feature repair");
            }
            return save(session);
        }
    }

    @Command(
            name = "history",
            description = "List stable feature IDs, definitions and rebuild diagnostics.",
            mixinStandardHelpOptions = true)
    static final class History extends FileCommand {

        History(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            var session = session();
            spec.commandLine()
                    .getOut()
                    .printf(
                            "document=%s units=mm cursor=%d features=%d%n",
                            session.document().id(),
                            session.document().cursor(),
                            session.document().features().size());
            try (var evaluation = session.evaluate(pluginDirectory)) {
                for (int i = 0; i < session.document().features().size(); i++) {
                    var diagnostic = evaluation.diagnostics().get(i);
                    var feature = session.document().features().get(i);
                    Object definition = feature;
                    if (diagnostic.state() == Evaluation.State.OK) {
                        if (BodyPattern.matches(feature)) {
                            definition = BodyPattern.from(feature);
                        } else if (BodyExtrusion.modifiesBody(feature)) {
                            definition = BodyExtrusion.from(feature);
                        } else if (feature instanceof Feature.Custom && CutTargets.through(feature)) {
                            definition = CutTargets.firstThroughCut(feature) + " targets="
                                    + CutTargets.throughTargets(feature).bodies();
                        }
                    }
                    spec.commandLine()
                            .getOut()
                            .printf(
                                    "%d %s %s %s name=%s%n",
                                    i + 1,
                                    diagnostic.state(),
                                    feature.id() + " " + definition,
                                    diagnostic.message(),
                                    session.document().featureName(diagnostic.featureId(), diagnostic.featureId()));
                }
                return evaluation.valid() ? 0 : 1;
            }
        }
    }

    @Command(
            name = "inspect",
            description = "Rebuild and measure active solids, or classify a point (in mm).",
            mixinStandardHelpOptions = true)
    static final class Inspect extends FileCommand {

        @Option(names = "--point", split = ",", arity = "1", description = "Classify x,y,z in each body")
        double[] point;

        Inspect(DocumentSource documents, DocumentSave documentWriter) {
            super(documents, documentWriter);
        }

        @Override
        public Integer call() throws Exception {
            if (point != null && point.length != 3) {
                throw new IllegalArgumentException("--point requires x,y,z");
            }
            var session = session();
            try (var evaluation = session.evaluate(pluginDirectory)) {
                spec.commandLine()
                        .getOut()
                        .println("revision="
                                + dev.voxelbrew.engine.persistence.DocumentCodec.fingerprint(session.document()));
                spec.commandLine()
                        .getOut()
                        .printf(
                                "document=%s kernel=%s units=mm cursor=%d%n",
                                session.document().id(),
                                Solid.kernelVersion(),
                                session.document().cursor());
                for (var body : evaluation.bodies()) {
                    var b = body.solid().bounds();
                    spec.commandLine()
                            .getOut()
                            .printf(
                                    Locale.ROOT,
                                    "body=%s output=%s/%s volume=%.12f bounds=%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                                    body.id(),
                                    body.output().featureId(),
                                    body.output().outputName(),
                                    body.solid().volume(),
                                    b.minX(),
                                    b.minY(),
                                    b.minZ(),
                                    b.maxX(),
                                    b.maxY(),
                                    b.maxZ());
                    var copy = evaluation.patternCopies().get(body.id());
                    if (copy != null) {
                        spec.commandLine().getOut().printf("source=%s copy=%d%n", copy.sourceId(), copy.index());
                    }
                    if (session.document().bodyNames().containsKey(body.id())) {
                        spec.commandLine()
                                .getOut()
                                .println(
                                        "name=" + session.document().bodyNames().get(body.id()));
                    }
                    if (point != null) {
                        spec.commandLine()
                                .getOut()
                                .println("contains=" + body.solid().contains(point[0], point[1], point[2]));
                    }
                }
                spec.commandLine()
                        .getOut()
                        .println("bodyCount=" + evaluation.bodies().size());
                return 0;
            }
        }
    }

    @Command(
            name = "native-demo",
            description = "Run the M0 real-kernel reference smoke demo.",
            mixinStandardHelpOptions = true)
    static final class NativeDemo implements Callable<Integer> {

        NativeDemo() {}

        @Override
        public Integer call() {
            try (Solid plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 8);
                    Solid drilled = plate.cutThroughCircle(10, 10, 4)) {
                System.out.printf(
                        Locale.ROOT,
                        "OCCT %s%nvolume=%.12f mm^3%nbounds=%s%nthroughHole=%s%n",
                        Solid.kernelVersion(),
                        drilled.volume(),
                        drilled.bounds(),
                        !drilled.contains(10, 10, 4));
            }
            return 0;
        }
    }

    @SuppressWarnings("try") // Keep the app image in place until the process has finished.
    public static void main(String[] args) {
        dev.voxelbrew.util.logging.Logging.initialize("cli");
        configureInstalledRuntime();
        int result;
        try (var lease = dev.voxelbrew.util.update.ApplicationImage.running(installationComponent())) {
            result = commandLine().execute(args);
        } catch (java.io.IOException | RuntimeException | LinkageError failure) {
            log.error("CLI startup or execution failed", failure);
            System.err.println("ERROR: " + failure.getMessage());
            result = 1;
        }
        log.info("CLI finished with exit code {}", result);
        dev.voxelbrew.util.logging.Logging.close();
        System.exit(result);
    }

    static dev.voxelbrew.util.update.ApplicationImage.Component installationComponent() {
        return Boolean.getBoolean("voxelbrew.cli.desktopInstallation")
                ? dev.voxelbrew.util.update.ApplicationImage.Component.GUI
                : dev.voxelbrew.util.update.ApplicationImage.Component.CLI;
    }

    public static CommandLine commandLine() {
        return CliComponent.factory()
                .create(
                        dev.voxelbrew.util.di.ApplicationModule.system(installationComponent()),
                        new dev.voxelbrew.cli.FileIoModule())
                .commandLine();
    }

    static CommandLine commandLine(CommandLine.IFactory factory, TranslationService translations) {
        var cliCommand = new CommandLine(VoxelBrew.class, factory)
                .addSubcommand("exporters", Exporters.class)
                .addSubcommand("export", Export.class)
                .setExecutionExceptionHandler((failure, command, parsed) -> {
                    if (failure instanceof IllegalArgumentException) {
                        log.warn("Command {} rejected: {}", command.getCommandName(), failure.getMessage());
                    } else {
                        log.error("Command {} failed", command.getCommandName(), failure);
                    }
                    command.getErr().println("ERROR: " + failure.getMessage());
                    return 1;
                });
        cliCommand.getCommandSpec().usageMessage().description(translations.translate("app.description"));
        PrintingCommands.register(cliCommand);
        ImportCommands.register(cliCommand);
        FeatureCommands.register(cliCommand);
        SketchCommands.register(cliCommand);
        ParameterCommands.register(cliCommand);
        UpdateCommands.register(cliCommand);
        cliCommand.setExecutionStrategy(parsed -> {
            var selected = parsed;
            while (selected.hasSubcommand()) selected = selected.subcommand();
            String command = selected.commandSpec().name();
            log.info("Executing CLI command {}", command);
            return new CommandLine.RunLast().execute(parsed);
        });
        WorkspaceCommands.install(cliCommand);
        return cliCommand;
    }

    private static void configureInstalledRuntime() {
        if (System.getProperty("voxelbrew.native.path") != null) {
            return;
        }
        try {
            Path jar = Path.of(VoxelBrew.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path parent = jar.getParent();
            Path runtime = parent == null ? null : parent.resolve("native");
            if (runtime != null && !Files.isDirectory(runtime)) {
                Path image = parent.getParent();
                runtime = image == null ? null : image.resolve("native");
            }
            if (runtime != null && Files.isDirectory(runtime)) {
                System.setProperty("voxelbrew.native.path", runtime.toString());
            }
        } catch (java.net.URISyntaxException ignored) {
            /* NativeKernel supplies the load diagnostic. */
        }
    }

    VoxelBrew() {}

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}

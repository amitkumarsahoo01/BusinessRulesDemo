package com.dmnrules.decision.model;

import com.dmnrules.decision.trace.TraceCapturingListener;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compiles every PUBLISHED model version from Postgres with {@code DMNRuntimeBuilder} and caches
 * the result, per the dynamic registry design in docs/Architecture.md (CLAUDE.md rule 4: models
 * load dynamically, no Kogito build-time codegen for these). Reloaded on startup and whenever
 * {@link com.dmnrules.decision.notify.ModelPublishedNotifier} observes a {@code model_published}
 * Postgres NOTIFY.
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final ModelVersionRepository repository;
    private final TraceCapturingListener traceListener = new TraceCapturingListener();

    private volatile Map<String, CompiledModel> byKeyVersion = Map.of();
    private volatile Map<String, String> latestPublishedVersion = Map.of();

    public ModelRegistry(ModelVersionRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reload();
    }

    public synchronized void reload() {
        List<ModelVersionRow> published = repository.findByStatus("PUBLISHED");
        Map<String, CompiledModel> newByKeyVersion = new HashMap<>();
        Map<String, String> newLatest = new HashMap<>();

        for (ModelVersionRow row : published) {
            try {
                CompiledModel compiled = compile(row);
                newByKeyVersion.put(key(row.modelKey(), row.version()), compiled);
                newLatest.merge(row.modelKey(), row.version(),
                        (a, b) -> compareVersions(a, b) >= 0 ? a : b);
            } catch (RuntimeException e) {
                log.error("Failed to compile published model {}@{}, skipping", row.modelKey(), row.version(), e);
            }
        }

        byKeyVersion = Map.copyOf(newByKeyVersion);
        latestPublishedVersion = Map.copyOf(newLatest);
        log.info("Model registry reloaded: {} published version(s) across {} model(s)",
                byKeyVersion.size(), latestPublishedVersion.size());
    }

    public CompiledModel resolve(String modelKey, Optional<String> version) {
        String resolvedVersion = version.orElseGet(() -> {
            String latest = latestPublishedVersion.get(modelKey);
            if (latest == null) {
                throw new ModelNotFoundException("No published version found for model '" + modelKey + "'");
            }
            return latest;
        });
        CompiledModel compiled = byKeyVersion.get(key(modelKey, resolvedVersion));
        if (compiled == null) {
            throw new ModelNotFoundException(
                    "Model '" + modelKey + "' version '" + resolvedVersion + "' is not published");
        }
        return compiled;
    }

    private CompiledModel compile(ModelVersionRow row) {
        Resource resource = ResourceFactory.newByteArrayResource(row.dmnXml().getBytes(StandardCharsets.UTF_8));
        resource.setSourcePath(row.modelKey() + "-" + row.version() + ".dmn");
        resource.setResourceType(ResourceType.DMN);

        DMNRuntime runtime = DMNRuntimeBuilder.fromDefaults().buildConfiguration()
                .fromResources(List.of(resource))
                .getOrElseThrow(e -> new IllegalStateException(
                        "Failed to compile DMN model " + row.modelKey() + "@" + row.version(), e));

        DMNModel model = runtime.getModels().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No DMN model found in resource for " + row.modelKey() + "@" + row.version()));

        List<DMNMessage> errors = model.getMessages(DMNMessage.Severity.ERROR);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "DMN model " + row.modelKey() + "@" + row.version() + " has compile errors: " + errors);
        }

        runtime.addListener(traceListener);
        return new CompiledModel(row.id(), row.modelKey(), row.version(), runtime, model);
    }

    private static String key(String modelKey, String version) {
        return modelKey + "@" + version;
    }

    public static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            int valueA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int valueB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            int cmp = Integer.compare(valueA, valueB);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}

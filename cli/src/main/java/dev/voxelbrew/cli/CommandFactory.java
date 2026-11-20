package dev.voxelbrew.cli;

import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

/** Picocli's construction boundary; business commands never look up services. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CommandFactory implements CommandLine.IFactory {
    private final Map<Class<?>, java.util.function.Supplier<Object>> commands;

    @Override
    public <K> K create(Class<K> type) throws Exception {
        var provider = commands.get(type);
        if (provider != null) return type.cast(provider.get());
        if (type.isAnnotationPresent(CommandLine.Command.class)
                && type.getPackageName().equals(VoxelBrew.class.getPackageName())) {
            throw new IllegalArgumentException("Missing command binding: " + type.getName());
        }
        return CommandLine.defaultFactory().create(type);
    }
}

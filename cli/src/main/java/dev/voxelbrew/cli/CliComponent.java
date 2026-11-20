package dev.voxelbrew.cli;

import dev.voxelbrew.api.i18n.TranslationService;
import dev.voxelbrew.util.di.ApplicationModule;
import picocli.CommandLine;

public interface CliComponent {
    interface Factory {
        CliComponent create(ApplicationModule application, FileIoModule files);
    }

    static Factory factory() {
        return dev.voxelbrew.util.services.Services.load(Factory.class);
    }

    CommandFactory commands();

    TranslationService translations();

    default CommandLine commandLine() {
        return VoxelBrew.commandLine(commands(), translations());
    }
}

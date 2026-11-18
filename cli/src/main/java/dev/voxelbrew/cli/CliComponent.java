package dev.voxelbrew.cli;

import dev.voxelbrew.util.di.ApplicationModule;
import dev.voxelbrew.util.i18n.Translations;
import picocli.CommandLine;

public interface CliComponent {
    static Factory factory() {
        return dev.voxelbrew.util.services.Services.load(Factory.class);
    }

    CommandFactory commands();

    Translations translations();

    default CommandLine commandLine() {
        return VoxelBrew.commandLine(commands(), translations());
    }

    interface Factory {
        CliComponent create(ApplicationModule application, FileIoModule files);
    }
}

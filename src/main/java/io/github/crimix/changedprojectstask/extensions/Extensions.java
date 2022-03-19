package io.github.crimix.changedprojectstask.extensions;

import io.github.crimix.changedprojectstask.configuration.ChangedProjectsChoice;
import io.github.crimix.changedprojectstask.configuration.ChangedProjectsConfiguration;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import java.io.File;
import java.util.Collections;
import java.util.Optional;

import static io.github.crimix.changedprojectstask.utils.Properties.CURRENT_COMMIT;
import static io.github.crimix.changedprojectstask.utils.Properties.ENABLE;
import static io.github.crimix.changedprojectstask.utils.Properties.PREVIOUS_COMMIT;

/**
 * Class the contains the Lombok extension methods
 */
public class Extensions {

    /**
     * Returns whether the project is the root project.
     * @return true if the project is the root project
     */
    public static boolean isRootProject(Project project) {
        return project.equals(project.getRootProject());
    }

    /**
     * Gets the name of the project's directory
     * @return the name of the project's directory
     */
    public static String getProjectDirName(Project project) {
        return project.getProjectDir().getName();
    }

    /**
     * Returns whether the plugin's task is allowed to run and configure.
     * @return true if the plugin's task is allowed to run and configure
     */
    public static boolean hasBeenEnabled(Project project) {
        return project.getRootProject().hasProperty(ENABLE);
    }

    /**
     * Gets the configured commit id
     * @return either an optional with the commit id or an empty optional if it has not been configured
     */
    public static Optional<String> getCommitId(Project project) {
        return Optional.of(project)
                .map(Project::getRootProject)
                .map(p -> p.findProperty(CURRENT_COMMIT))
                .map(String.class::cast);
    }

    /**
     * Gets the configured previous commit id
     * @return either an optional with the previous commit id or an empty optional if it has not been configured
     */
    public static Optional<String> getPreviousCommitId(Project project) {
        return Optional.of(project)
                .map(Project::getRootProject)
                .map(p -> p.findProperty(PREVIOUS_COMMIT))
                .map(String.class::cast);
    }

    /**
     * Finds the git root for the project.
     * @return a file that represents the git root of the project.
     */
    public static File getGitRootDir(Project project) {
        File currentDir = project.getRootProject().getProjectDir();

        //Keep going until we either hit a .git dir or the root of the file system on either Windows or Linux
        while (currentDir != null && !currentDir.getPath().equals("/"))  {
            if (new File(String.format("%s/.git", currentDir.getPath())).exists()) {
                return currentDir;
            }
            currentDir = currentDir.getParentFile();
        }

        return null;
    }

    /**
     * Runs validation on the configuration.
     */
    public static void validate(ChangedProjectsConfiguration configuration) {
        String taskToRun = configuration.getTaskToRun().getOrNull();
        if (taskToRun == null) {
            throw new IllegalArgumentException("changedProjectsTask: taskToRun is required");
        } else if (taskToRun.startsWith(":")) {
            throw new IllegalArgumentException("changedProjectsTask: taskToRun should not start with :");
        }
        configuration.getAlwaysRunProject().getOrElse(Collections.emptySet());
        configuration.getAffectsAllRegex().getOrElse(Collections.emptySet()); //Gradle will throw if the type does not match
        configuration.getIgnoredRegex().getOrElse(Collections.emptySet()); //Gradle will throw if the type does not match
        String mode = configuration.getChangedProjectsMode().getOrElse(ChangedProjectsChoice.INCLUDE_DEPENDENTS.name());
        try {
            ChangedProjectsChoice.valueOf(mode);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException(String.format("changedProjectsTask: ChangedProjectsMode must be either %s or %s ",ChangedProjectsChoice.ONLY_DIRECTLY.name(), ChangedProjectsChoice.INCLUDE_DEPENDENTS.name()));
        }
    }

    /**
     * Gets the plugin's configured mode
     * @return the mode the plugin is configured to use
     */
    public static ChangedProjectsChoice getPluginMode(ChangedProjectsConfiguration configuration) {
        return ChangedProjectsChoice.valueOf(configuration.getChangedProjectsMode().getOrElse(ChangedProjectsChoice.INCLUDE_DEPENDENTS.name()));
    }

    /**
     * Prints the configuration.
     * @param logger the logger to print the configuration to.
     */
    public static void print(ChangedProjectsConfiguration configuration, Logger logger) {
        if (shouldLog(configuration)) {
            logger.lifecycle("Printing configuration");
            logger.lifecycle("Task to run {}", configuration.getTaskToRun().getOrNull());
            logger.lifecycle("Always run project {}", configuration.getAlwaysRunProject().getOrElse(Collections.emptySet()));
            logger.lifecycle("Affects all regex {}", configuration.getAffectsAllRegex().getOrElse(Collections.emptySet()));
            logger.lifecycle("Ignored regex {}", configuration.getIgnoredRegex().getOrElse(Collections.emptySet()));
            logger.lifecycle("Mode {}", getPluginMode(configuration));
            logger.lifecycle("");
        }
    }

    /**
     * Returns whether the plugin should log debug information to the Gradle log
     * @return true if the plugin should debug log
     */
    public static boolean shouldLog(ChangedProjectsConfiguration configuration) {
        return configuration.getDebugLogging().getOrElse(false);
    }
}
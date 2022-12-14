package io.github.mklueh.affected;

import io.github.mklueh.affected.configuration.*;
import io.github.mklueh.affected.providers.ChangedFilesProvider;
import io.github.mklueh.affected.providers.ProjectDependencyProvider;
import io.github.mklueh.affected.utils.Extension;
import io.github.mklueh.affected.utils.LogUtil;
import io.github.mklueh.affected.utils.LoggingOutputStream;
import lombok.SneakyThrows;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.gradle.configurationcache.DefaultConfigurationCacheKt.getLogger;

/**
 * TODO name AffectedTask makes no sense for this class and confuses
 */
public class AffectedTaskRunner {

    private final Logger logger;
    private final Project project;
    private final Task affectedTask;
    private AffectedProjectExtension extension;
    private final AffectedExtension configuration;

    private boolean affectsAll = false;

    // may run if affected
    private Set<Project> allowedToRunProjects = new HashSet<>();

    //always run, affected or not
    private Set<Project> alwaysRunProjects = new HashSet<>();

    //never run, affected or not - but dependents still will
    private Set<Project> neverRunProjects = new HashSet<>();

    private Set<Project> affectedProjects = new HashSet<>();

    private AffectedTaskRunner(Project project, Task affectedTask, AffectedExtension configuration) {
        this.project = project;
        this.logger = project.getLogger();
        this.configuration = configuration;
        this.affectedTask = affectedTask;
    }

    public static void configureAndRun(Project project, Task task, AffectedExtension configuration) {
        AffectedTaskRunner affectedTaskRunner = new AffectedTaskRunner(project, task, configuration);

        if (!Extension.shouldUseCommandLine(project)) {
            affectedTaskRunner.configureTargetTasksForProjects();
        }

        //pure evaluation that enabled or disables the previously configured target tasks
        project.getGradle().projectsEvaluated(g -> affectedTaskRunner.evaluateAffectedProjects());
    }

    private void afterEvaluate() {
        evaluateAffectedProjects();
        if (Extension.shouldUseCommandLine(project)) {
            commandLineRunProjects();
        }
    }

    private void commandLineRunProjects() {
        for (Project project : project.getAllprojects()) {
            if (shouldProjectRun(project)) {
                runCommandLineOnProject(project);
            }
        }
    }

    /**
     * TODO determine everything that should run before looping through the projects and run the actual task.
     * Testability
     */
    private void configureTargetTasksForProjects() {
        for (Project project : project.getAllprojects()) {

            AffectedProjectExtension extension = project.getExtensions()
                    .create("affectedProject", AffectedProjectExtension.class);

            extension.getIsRunnableProject().convention(false);


            project.afterEvaluate(p -> {

                Task targetTask = p.getTasks().findByPath(resolvePathToTargetTask(p));

                if (targetTask == null) throw new RuntimeException("affected plugin called without target task");

                logger.lifecycle(targetTask.getPath());

                if (!extension.getEnabled().getOrElse(true)) {
                    logger.lifecycle("affected plugin: disabled for " + p.getName());
                    return;
                }

                //is it a problem that it depends on multiple tasks at the same time?
                //make targetTask run after changedProjectsTask
                affectedTask.dependsOn(targetTask);

                //conditionally enable / disable the specific project's task
                targetTask.onlyIf(t -> shouldProjectRun(p));
            });
        }
    }

    private boolean shouldProjectRun(Project p) {
        //preventing conditions
        if (neverRunProjects.contains(p)) {
            logger.lifecycle("affected plugin: " + p.getName() + " won't run - (neverRun)");
            return false;
        }

        if (!allowedToRunProjects.contains(p)) return false;


        boolean willRun = false;

        //allowing conditions
        //noinspection RedundantIfStatement
        if (affectsAll) {
            //willRun = true;
        }

        if (alwaysRunProjects.contains(p)) {
            willRun = true;
        }

        if (affectedProjects.contains(p)) {
            logger.lifecycle("affected plugin: " + p.getName() + " is affected");
            willRun = true;
        }

        return willRun;
    }

    @SneakyThrows
    private void runCommandLineOnProject(Project affected) {
        String commandLine = String.format("%s %s %s", getGradleWrapper(), resolvePathToTargetTask(affected), Extension.getCommandLineArgs(project));

        getLogger().lifecycle("Running {}", commandLine);

        LoggingOutputStream stdout = new LoggingOutputStream(project.getLogger()::lifecycle);
        LoggingOutputStream stderr = new LoggingOutputStream(project.getLogger()::error);
        //We use Apache Commons Exec because we do not want to re-invent the wheel as ProcessBuilder hangs if the output or error buffer is full
        DefaultExecutor exec = new DefaultExecutor();
        exec.setStreamHandler(new PumpStreamHandler(stdout, stderr));
        exec.setWorkingDirectory(project.getRootProject().getProjectDir());
        int exitValue = exec.execute(CommandLine.parse(commandLine));

        if (exitValue != 0) {
            throw new IllegalStateException("Executing command failed");
        }
    }

    private String getGradleWrapper() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return "gradlew.bat";
        } else {
            return "./gradlew";
        }
    }

    /**
     * TODO naming - what do we do here?
     */
    private void evaluateAffectedProjects() {
        Project project = getRootProject();
        ConfigurationValidator.validate(configuration, project);

        if (!isAffectedPluginEnabled()) {
            logger.lifecycle("affected plugin: disabled");
            return;
        }

        ChangedFilesProvider changedFilesProvider = new ChangedFilesProvider(project, configuration);
        changedFilesProvider.printDebug();

        if (!changedFilesProvider.hasFileChanges()) {
            logger.lifecycle("affected plugin: no changed files detected");
            return;
        }

        //must be called before termination if all affected
        determineEligibleProjectsBasedOnProperties(project);

        if (changedFilesProvider.allProjectsAffected()) {
            affectsAll = true;
            logger.lifecycle("affected plugin: all projects are affected");
            return;
        }

        ProjectDependencyProvider projectDependencyProvider = new ProjectDependencyProvider(project, configuration);
        projectDependencyProvider.printDebug();

        Set<Project> directlyAffectedProjects = findAffectedProjects(changedFilesProvider, projectDependencyProvider);

        //TODO which file affects which projects?
        if (LogUtil.shouldLog(configuration)) {
            logger.lifecycle("affected plugin: directly affected projects: {}", directlyAffectedProjects);
        }

        Set<Project> dependentAffectedProjects = new HashSet<>();
        if (AffectedMode.INCLUDE_DEPENDENTS == PropertiesExtractor.getPluginMode(configuration)) {
            dependentAffectedProjects.addAll(projectDependencyProvider.getAffectedDependentProjects(directlyAffectedProjects));
            if (LogUtil.shouldLog(configuration)) {
                logger.lifecycle("affected plugin: dependent affected Projects: {}", dependentAffectedProjects);
            }
        }

        affectedProjects = Stream.concat(directlyAffectedProjects.stream(), dependentAffectedProjects.stream()).collect(Collectors.toSet());
    }

    private Set<Project> findAffectedProjects(ChangedFilesProvider changedFilesProvider, ProjectDependencyProvider projectDependencyProvider) {
        return changedFilesProvider
                .getChangedFiles()
                .stream()
                .map(projectDependencyProvider::findProjectOfChangedFile)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Matches projects against the "alwaysRun" and "neverRun" projects and
     */
    private void determineEligibleProjectsBasedOnProperties(Project project) {
        //should run no matter what
        Set<String> alwaysRun = configuration.getAlwaysRunProjects().getOrElse(Collections.emptySet());
        Set<Project> allProjects = project.getAllprojects();
        alwaysRunProjects = allProjects.stream().filter(p -> alwaysRun.contains(p.getPath())).collect(Collectors.toSet());

        //should never run
        Set<String> notAllowedToRun = configuration.getNeverRunProjects().getOrElse(Collections.emptySet());
        neverRunProjects = allProjects.stream().filter(p -> notAllowedToRun.contains(p.getName())).collect(Collectors.toSet());

        logger.lifecycle("affected plugin: never run projects size - [" + neverRunProjects
                .stream().map(Project::getName).collect(Collectors.joining(",")) + "]");

        //only those should be allowed to run if set
        Set<String> allowedToRun = PropertiesExtractor.getEnabledModulesParameter(project)
                .orElse(configuration.getProjects().getOrElse(Collections.emptySet()));

        allowedToRunProjects = allProjects.stream().filter(p -> allowedToRun.contains(p.getName())).collect(Collectors.toSet());
        logger.lifecycle("affected plugin: allowedToRun - " + allowedToRunProjects.stream().map(Project::getName)
                .collect(Collectors.joining(",")));

        if (LogUtil.shouldLog(configuration)) {
            logger.lifecycle("Projects allowed to run: {}", allowedToRunProjects);
            logger.lifecycle("Never run projects: {}", this.neverRunProjects);
            logger.lifecycle("Always run projects: {}", this.alwaysRunProjects);
        }
    }

    private Project getRootProject() {
        return project.getRootProject();
    }

    private boolean isAffectedPluginEnabled() {
        return Extension.isAffectedPluginEnabled(project);
    }


    private String resolvePathToTargetTask(Project project) {
        String targetTask = PropertiesExtractor.getTargetTaskParameter(project)
                .orElse(configuration.getTarget().getOrNull());

        if (LogUtil.shouldLog(configuration)) {
            logger.lifecycle("targetTask: {}", targetTask);
        }

        if (Extension.isRootProject(project)) {
            return String.format(":%s", targetTask);
        }

        return String.format("%s:%s", project.getPath(), targetTask);
    }

}

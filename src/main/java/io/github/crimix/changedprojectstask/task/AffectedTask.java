package io.github.crimix.changedprojectstask.task;

import io.github.crimix.changedprojectstask.configuration.AffectedMode;
import io.github.crimix.changedprojectstask.configuration.Configuration;
import io.github.crimix.changedprojectstask.configuration.PropertiesExtractor;
import io.github.crimix.changedprojectstask.providers.ChangedFilesProvider;
import io.github.crimix.changedprojectstask.providers.ProjectDependencyProvider;
import io.github.crimix.changedprojectstask.utils.LogUtil;
import io.github.crimix.changedprojectstask.configuration.ConfigurationValidator;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AffectedTask {

    private final Logger logger;
    private final Project project;
    private final Task affectedTask;
    private final Configuration configuration;

    private boolean affectsAll = false;

    // may run if affected
    private Set<Project> mayRunProjects = new HashSet<>();

    //always run, affected or not
    private Set<Project> alwaysRunProjects = new HashSet<>();

    //never run, affected or not - but dependents still will
    private Set<Project> neverRunProjects = new HashSet<>();

    private Set<Project> affectedProjects = new HashSet<>();

    private AffectedTask(Project project, Task affectedTask, Configuration configuration) {
        this.project = project;
        this.logger = project.getLogger();
        this.configuration = configuration;
        this.affectedTask = affectedTask;
    }

    public static void configureAndRun(Project project, Task task, Configuration configuration) {
        AffectedTask affectedTask = new AffectedTask(project, task, configuration);

        affectedTask.configureTargetTasksForProjects();

        project.getGradle().projectsEvaluated(g -> affectedTask.configureAfterAllEvaluate());
    }

    private void configureTargetTasksForProjects() {
        for (Project project : project.getAllprojects()) {
            project.afterEvaluate(p -> {
                Task targetTask = p.getTasks().findByPath(resolvePathToTargetTask(p));

                if (targetTask != null) {
                    //make targetTask run after changedProjectsTask
                    affectedTask.dependsOn(targetTask);
                    targetTask.onlyIf(t -> shouldProjectRun(p));
                }
            });
        }
    }

    private boolean shouldProjectRun(Project p) {
        return !neverRunProjects.contains(p)
                && (mayRunProjects.isEmpty() || mayRunProjects.contains(p))
                && (affectsAll || affectedProjects.contains(p) || alwaysRunProjects.contains(p));
    }

    private void configureAfterAllEvaluate() {
        Project project = getRootProject();
        ConfigurationValidator.validate(configuration, project);

        if (hasBeenEnabled()) {
            ChangedFilesProvider changedFilesProvider = new ChangedFilesProvider(project, configuration);
            changedFilesProvider.printDebug();

            if (changedFilesProvider.getChangedFiles().isEmpty() && !changedFilesProvider.isAllProjectsAffected()) {
                return; //If there are no changes, and we are not forced to run all projects, just skip the rest of the configuration
            }

            configureAlwaysAndNeverRun(project);

            // If we have already determined that we should run all, then no need to spend more time on finding the specific projects
            if (changedFilesProvider.isAllProjectsAffected()) {
                affectsAll = true;
            } else {
                ProjectDependencyProvider projectDependencyProvider = new ProjectDependencyProvider(project, configuration);
                projectDependencyProvider.printDebug();

                Set<Project> directlyAffectedProjects = evaluateDirectAffectedProjects(changedFilesProvider, projectDependencyProvider);

                if (LogUtil.shouldLog(configuration)) {
                    logger.lifecycle("Directly affected projects: {}", directlyAffectedProjects);
                }

                Set<Project> dependentAffectedProjects = new HashSet<>();
                if (AffectedMode.INCLUDE_DEPENDENTS == PropertiesExtractor.getPluginMode(configuration)) {
                    dependentAffectedProjects.addAll(projectDependencyProvider.getAffectedDependentProjects(directlyAffectedProjects));
                    if (LogUtil.shouldLog(configuration)) {
                        logger.lifecycle("Dependent affected Projects: {}", dependentAffectedProjects);
                    }
                }

                affectedProjects = Stream.concat(directlyAffectedProjects.stream(), dependentAffectedProjects.stream())
                        .collect(Collectors.toSet());
            }
        }
    }

    private Set<Project> evaluateDirectAffectedProjects(ChangedFilesProvider changedFilesProvider, ProjectDependencyProvider projectDependencyProvider) {
        return changedFilesProvider.getChangedFiles().stream()
                .map(projectDependencyProvider::getChangedProject)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void configureAlwaysAndNeverRun(Project project) {
        //should run no matter what
        Set<String> alwaysRunPath = configuration.getAlwaysRunProject().getOrElse(Collections.emptySet());
        alwaysRunProjects = project.getAllprojects().stream()
                .filter(p -> alwaysRunPath.contains(p.getPath()))
                .collect(Collectors.toSet());

        //should never run
        Set<String> neverRunPath = configuration.getNeverRunProject().getOrElse(Collections.emptySet());
        neverRunProjects = project.getAllprojects().stream()
                .filter(p -> neverRunPath.contains(p.getPath()))
                .collect(Collectors.toSet());


        //only those should be allowed to run if set
        Set<String> onlyRun = configuration.getProjects().getOrElse(Collections.emptySet());
        mayRunProjects = project.getAllprojects().stream()
                .filter(p -> onlyRun.contains(p.getPath()))
                .collect(Collectors.toSet());

        if (LogUtil.shouldLog(configuration)) {
            logger.lifecycle("May run projects: {}", mayRunProjects);
            logger.lifecycle("Never run projects: {}", neverRunProjects);
            logger.lifecycle("Always run projects: {}", alwaysRunProjects);
        }
    }

    private Project getRootProject() {
        return project.getRootProject();
    }

    private boolean hasBeenEnabled() {
        return PropertiesExtractor.hasBeenEnabled(project);
    }


    private String resolvePathToTargetTask(Project project) {
        String targetTask = PropertiesExtractor
                .getTargetTaskParameter(project)
                .orElse(configuration.getTarget().getOrNull());

        if (LogUtil.shouldLog(configuration)) {
            logger.lifecycle("targetTask: {}", targetTask);
        }

        if (PropertiesExtractor.isRootProject(project)) {
            return String.format(":%s", targetTask);
        } else {
            return String.format("%s:%s", project.getPath(), targetTask);
        }
    }

}
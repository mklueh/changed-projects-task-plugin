package io.github.mklueh.affected.configuration;

/**
 * Configurable properies that can be used in gralde using the -P prefix
 * Like -PchangedProjectsTask.enable
 */
public class Properties {
    private static final String PREFIX = "affected.";

    //TODO try to get rid of this unnecessary property
    public static final String ENABLE = PREFIX + "run";

    public static final String CURRENT_COMMIT = PREFIX + "commit";

    public static final String PREVIOUS_COMMIT = PREFIX + "prevCommit";

    public static final String COMMIT_MODE = PREFIX + "compareMode";

    public static final String TARGET_TASK = PREFIX + "target";

    public static final String ENABLED_FOR_MODULES = PREFIX + "projects";

    public static final String ENABLED_FOR_ALL_MODULES = PREFIX + "all";

    public static final String ENABLE_COMMANDLINE = PREFIX + "runCommandLine";

    public static final String COMMANDLINE_ARGS = PREFIX + "commandLineArgs";

}

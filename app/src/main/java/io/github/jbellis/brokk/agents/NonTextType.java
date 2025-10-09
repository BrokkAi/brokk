package io.github.jbellis.brokk.agents;

/** Classification of non-text merge conflicts that require specialized handling. */
public enum NonTextType {
    NONE,
    DELETE_MODIFY,
    RENAME_MODIFY,
    RENAME_RENAME,
    FILE_DIRECTORY,
    ADD_ADD_BINARY,
    MODE_BIT,
    SUBMODULE_CONFLICT
}

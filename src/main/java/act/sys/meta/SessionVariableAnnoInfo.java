package act.sys.meta;

import act.inject.SessionVariable;

/**
 * Capture {@link SessionVariable} annotation data
 */
public class SessionVariableAnnoInfo {
    private String name;

    public SessionVariableAnnoInfo(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}

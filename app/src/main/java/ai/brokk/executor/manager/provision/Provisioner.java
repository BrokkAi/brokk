package ai.brokk.executor.manager.provision;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Provisioner creates isolated execution environments for sessions.
 * Implementations might use git worktrees, containers, VMs, or other isolation mechanisms.
 */
public interface Provisioner {
    /**
     * Provision a new execution environment for the given session.
     * This method must be idempotent: calling it multiple times with the same sessionId
     * should return the same workspace path without creating duplicate environments.
     *
     * @param spec the session specification containing provision parameters
     * @return the workspace path for the provisioned environment
     * @throws ProvisionException if provisioning fails
     */
    Path provision(SessionSpec spec) throws ProvisionException;

    /**
     * Perform a health check on the provisioner.
     * This should verify that the provisioner is in a healthy state and can provision new environments.
     *
     * @return true if healthy, false otherwise
     */
    boolean healthcheck();

    /**
     * Tear down and clean up the environment for the given session.
     * This method should be idempotent: calling it on a non-existent session should succeed without error.
     *
     * @param sessionId the session ID to tear down
     * @throws ProvisionException if teardown fails
     */
    void teardown(UUID sessionId) throws ProvisionException;

    /**
     * Exception thrown when provisioning or teardown operations fail.
     */
    class ProvisionException extends Exception {
        public ProvisionException(String message) {
            super(message);
        }

        public ProvisionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

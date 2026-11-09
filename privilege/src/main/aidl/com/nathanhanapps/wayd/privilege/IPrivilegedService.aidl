package com.nathanhanapps.wayd.privilege;

/**
 * Runs inside Shizuku's shell-privileged process. There is deliberately NO generic exec method: every call is a fixed,
 * validated command (docs/04, AGENTS.md rule 3). Add a method here only together with a Command entry and a test.
 */
interface IPrivilegedService {
    /** Shizuku reserves transaction 16777114 for destroy(); the service must stop itself when called. */
    void destroy() = 16777114;

    /** `dumpsys appops --history --package <pkg>`; returns "" if [pkg] is not a valid package name. */
    String appOpsHistory(String pkg) = 1;

    /** `cmd appops get <pkg>`: current modes plus last access / reject times and durations. */
    String appOpsGet(String pkg) = 2;
}

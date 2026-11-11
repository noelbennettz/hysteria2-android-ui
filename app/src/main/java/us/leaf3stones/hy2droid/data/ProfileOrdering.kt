package us.leaf3stones.hy2droid.data

/**
 * Sorting helpers for the profile list. Pure Kotlin: no Android, no Compose.
 */
object ProfileOrdering {

    /**
     * Order by measured latency, fastest first, when the user asked for latency
     * testing. Nodes without a result (never tested or the probe failed) keep
     * their original order and come after the measured ones, so a failed probe
     * never pushes a node to the top.
     *
     * `sortedWith` is stable, so equal (and unmeasured) entries keep the order
     * the store returned them in.
     */
    fun byLatency(profiles: List<Profile>, latency: Map<String, Int?>): List<Profile> =
        profiles.sortedWith(
            compareBy(
                { latency[it.id] == null },
                { latency[it.id] ?: Int.MAX_VALUE },
            ),
        )
}

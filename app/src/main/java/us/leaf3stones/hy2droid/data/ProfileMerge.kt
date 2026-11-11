package us.leaf3stones.hy2droid.data

/**
 * Rules for folding an imported profile (deep link, QR code, editor paste) into
 * the stored list. Pure Kotlin so the behaviour is unit-testable.
 *
 * Re-importing the same share link used to append a second, identical node — and
 * after a device migration, where the secrets are gone, it appended a *broken*
 * node next to the old one. Matching on the node identity instead updates the
 * existing entry.
 */
object ProfileMerge {

    /** True when both profiles point at the same server and port. */
    fun sameNode(existing: Profile, incoming: Profile): Boolean =
        existing.port == incoming.port &&
            existing.server.trim().equals(incoming.server.trim(), ignoreCase = true)

    /**
     * Fold [incoming] into [existing]: the id (and therefore the position and the
     * selection) is kept, and a blank field in the link never erases a stored
     * value — a bare `hysteria2://@host:port` re-import must not wipe the password.
     */
    fun merge(existing: Profile, incoming: Profile): Profile = incoming.copy(
        id = existing.id,
        name = incoming.name.ifBlank { existing.name },
        auth = incoming.auth.ifBlank { existing.auth },
        sni = incoming.sni.ifBlank { existing.sni },
        obfsPassword = incoming.obfsPassword.ifBlank { existing.obfsPassword },
    )

    /** The profile to store for [incoming] given the [existing] list. */
    fun resolve(existing: List<Profile>, incoming: Profile): Profile =
        existing.firstOrNull { sameNode(it, incoming) }
            ?.let { merge(it, incoming) }
            ?: incoming
}

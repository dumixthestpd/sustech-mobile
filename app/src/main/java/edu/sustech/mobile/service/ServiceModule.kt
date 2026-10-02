package edu.sustech.mobile.service

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/**
 * One SUSTech service in the app shell.
 *
 * The shell is deliberately service-agnostic: [Services] holds the catalog,
 * [edu.sustech.mobile.ui.ServicesFragment] renders it, and
 * [edu.sustech.mobile.ui.ServiceActivity] hosts whichever module was tapped.
 * Adding a service means adding one entry here plus its `root` fragment —
 * nothing in the shell changes.
 *
 * A module with `available = false` is roadmap, not a broken feature: it shows
 * in the catalog as planned and cannot be opened. An implemented module can
 * still depend on an external service being reachable; that live state belongs
 * to the service page, not this catalog flag.
 */
data class ServiceModule(
    /** Stable id, also the `--es service <id>` extra the harness uses. */
    val id: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
    @IdRes val navId: Int,
    val available: Boolean = false,
    val root: (() -> Fragment)? = null,
)

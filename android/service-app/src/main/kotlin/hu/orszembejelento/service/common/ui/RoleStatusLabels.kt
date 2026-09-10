package hu.orszembejelento.service.common.ui

import androidx.annotation.StringRes
import hu.orszembejelento.service.R

/**
 * The single place backend role / user-status enum values are turned into Hungarian for the
 * screen. Nothing else in the app should render `SERVICE_USER` / `ACTIVE` / … directly.
 *
 * Presentation only: the enum strings themselves are still what every request body and query
 * carries — this never touches the wire.
 */
@StringRes
fun roleLabelRes(role: String): Int = when (role) {
    "SERVICE_USER" -> R.string.role_service_user
    "MODERATOR" -> R.string.role_moderator
    "SUPER_ADMIN" -> R.string.role_super_admin
    // An unknown role from a newer backend still gets a safe, non-leaking label.
    else -> R.string.role_service_user
}

@StringRes
fun userStatusLabelRes(status: String): Int = when (status) {
    "ACTIVE" -> R.string.user_status_active
    "DEACTIVATED" -> R.string.user_status_deactivated
    else -> R.string.user_status_active
}

package hu.orszembejelento.service.common.ui

/** The service ID remains canonical; an optional nickname is only appended for display. */
fun userIdentityLabel(serviceId: String, nickname: String?): String =
    nickname?.takeIf(String::isNotBlank)?.let { "$serviceId(${it.trim()})" } ?: serviceId

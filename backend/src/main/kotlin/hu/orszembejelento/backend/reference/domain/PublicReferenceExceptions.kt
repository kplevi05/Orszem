package hu.orszembejelento.backend.reference.domain

/** The `query` parameter of the public settlement search is shorter than the minimum. */
class SettlementQueryTooShortException(val minimumCodePoints: Int) :
    RuntimeException("query must be at least $minimumCodePoints Unicode code point(s)")

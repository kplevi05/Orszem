package hu.orszembejelento.backend.common.web

/**
 * The single definition of the public HTTP prefix.
 *
 * The product is "V2", but the HTTP API generation is v1: this is the first published
 * Őrszem contract. A future breaking change would introduce `/api/v2` alongside it,
 * it does not renumber because the product version changed.
 */
object ApiPaths {
    const val V1 = "/api/v1"
}

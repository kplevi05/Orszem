/**
 * The browser always talks to the API on its own origin.
 *
 * In production Caddy reverse-proxies `/api/*` on https://orszembejelento.hu to the
 * backend; in development the Vite dev server does the same. Because the request never
 * crosses an origin, no CORS header is required and none is configured - a permissive
 * `Access-Control-Allow-Origin` would be a security regression, not a convenience.
 *
 * The Android clients use https://api.orszembejelento.hu instead. Both routes reach the
 * same backend and the same use cases.
 */
export const API_BASE_PATH = '/api/v1'

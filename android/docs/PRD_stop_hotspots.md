# PRD — Compare similar trips to find where time is lost stopped

## Problem
Every trip already records where it stopped and for how long (`stops`:
lat/lon/dur, via `TrackingService.addStopPoint`) and its full route
(`path`). None of that gets used across trips - the LOGS tab lists trips
individually and the MAP tab reviews exactly one trip at a time
(`viewTrip`). There's no way to answer "where do I usually get stuck,"
only "where did I get stuck on this one trip."

## Design

### Defining "similar trips"
Two trips are "similar" here if they share roughly the same start point
**and** roughly the same end point - modeling a repeated commute, not
just any two trips that happen to cross paths somewhere. This was a real
design choice, not the only reasonable one (an alternative would be
comparing all stops across every trip regardless of route) - scoped to
matching endpoints specifically so a stop hotspot on your regular commute
isn't diluted or drowned out by unrelated one-off errands, and so a
one-off trip's incidental stop doesn't get miscounted as part of a
pattern that doesn't exist.

`groupTripsByRoute()` (`assets/index.html`) does this as a single pass:
each trip joins the first existing group whose *anchor* trip (the first
trip chronologically in that group) is within
`ROUTE_ENDPOINT_MATCH_METERS` (400m) of it at both the start and the end;
otherwise it starts a new group. Groups that never reach
`MIN_TRIPS_PER_ROUTE_GROUP` (2) are dropped - a route with only one trip
recorded has nothing to compare yet.

This is intentionally not real clustering (no k-means, no re-anchoring
as more trips arrive) - a route group's anchor is fixed at whichever
trip started it. For the expected data shape (a handful of regular
routes, each with a modest number of trips) this is simple, cheap, and
good enough; it could drift if an anchor trip's endpoints are themselves
unusually noisy, which is a known, disclosed limitation rather than
something worked around.

### Ranking stop hotspots within a group
`clusterStops()` groups every stop from every trip in a route group by
proximity (`STOP_CLUSTER_RADIUS_METERS`, 75m) into hotspot clusters, then
ranks by **total duration across the group**, not any single trip's
worst stop - tracking `tripCount` (distinct trips that hit this spot) and
`avgDur` alongside `totalDur` so "6 of 7 trips, avg 3m 6s" is legible on
its own, not just a raw total that could come from one outlier.

### Where it lives
A new STOPS nav tab (`assets/index.html` only - the top-level prototype
`index.html`/`manifest.json` at the repo root already predates the
native rewrite and isn't kept in sync, same scoping the README already
documents for other features). Pulled fresh each time the tab opens
(`renderStopStats()`), same pattern as LOGS/SYS - trip data only changes
when a trip finishes, not continuously, so there's nothing to keep
live-synced while looking at a different tab.

Tapping a hotspot (`viewHotspot`) jumps to the MAP tab and highlights it
with a circle sized to `STOP_CLUSTER_RADIUS_METERS`, reusing the same
"clear old layers, hide the live position marker" pattern `viewTrip()`
already established (see `docs/PRD_map_view_review.md`) - a hotspot being
reviewed has the exact same "this isn't the live position" problem a
single trip's route review already had and already fixed.

## Non-goals
- No new native code or change to what gets recorded per trip - this
  runs entirely against `getTripsJson()`, which already exposes
  everything needed.
- No retroactive re-clustering as new trips arrive after a route group
  already formed beyond what re-running `renderStopStats()` naturally
  does each time the tab opens (it recomputes from scratch every time,
  so this isn't actually a gap in practice - noted for clarity, not as a
  disclosed limitation).
- No user-facing controls for the three matching constants
  (`ROUTE_ENDPOINT_MATCH_METERS`/`STOP_CLUSTER_RADIUS_METERS`/
  `MIN_TRIPS_PER_ROUTE_GROUP`) - they're reasoned defaults, not yet
  tuned against real recorded trips.
- Not touching the top-level prototype `index.html` at the repo root.

## Acceptance criteria
- A route driven only once produces no STOPS tab entry (nothing to
  compare yet).
- A route driven twice or more with a genuinely repeated stop (e.g. the
  same intersection both times) ranks that stop first, with an accurate
  trip count and total/average duration.
- A one-off trip whose endpoints don't match any repeated route never
  contributes to a hotspot cluster it has no business being grouped
  into.
- Tapping a hotspot shows the correct location on the MAP tab with no
  leftover live-marker or previously-reviewed-trip layer.

## What this PRD can't verify from this sandbox
No Android device/emulator here, and no real `trips.json` to test
against - same limitation as `PRD_map_view_review.md` and
`PRD_gps_accuracy_fix.md`. Verified by tracing `groupTripsByRoute()` /
`clusterStops()` / `renderStopStats()` / `viewHotspot()` by hand against
constructed trip data, and by running the extracted `<script>` block
through `node --check` for syntax validity - not by seeing it render
against real recorded trips.

## Implementation checklist
- [x] `groupTripsByRoute()` - single-pass endpoint matching
- [x] `clusterStops()` - proximity clustering + total/avg/tripCount
      ranking
- [x] STOPS nav tab, `page-stops`/`stops-list`, wired into `tab()`
- [x] `renderStopStats()` - route-group cards with top 5 hotspots each
- [x] `viewHotspot()` - jumps to MAP tab, highlights the spot, reuses
      the existing live-marker-hiding pattern
- [x] `.nav-bar` grid updated from 4 to 5 columns
- [x] README section documenting the feature and its matching
      thresholds
- [x] Manual verification: hand-traced logic + `node --check` on the
      extracted script; commit

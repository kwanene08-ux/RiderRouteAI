# Rider Route AI

Android 15 / target SDK 36 rider route assistant.

## Current version
- V0.5.0
- Screenshot picker + on-device Google ML Kit OCR
- Pickup/drop-off extraction and address cleanup
- Geocoding and basic distance estimate
- Google Maps navigation handoff
- Live fetch of Bangkok Drainage Department flood page
- Filters flood records to the current Thai date before displaying them
- Shows latest timestamp readable from today's records
- Shows today's reported flood stations and water level when available
- Stale/unavailable data never becomes a claim that the road is clear

## Flood safety rule
Flood information must be from the current date. If the source does not provide a current-date record or cannot be fetched, the app explicitly says it cannot confirm road safety.

The current implementation reads the Bangkok Drainage Department public flood-status page. This is a station feed, not a guarantee for every meter of a route. Route-specific matching and map-based risk scoring are the next milestone.

## Roadmap
- V0.5: route geometry + traffic-aware ETA + route-specific flood matching
- V0.6: multiple flood sources + freshness/stale threshold + source health
- V1: route risk score and alternative route suggestions
- V2: rider job heatmap + post-delivery recommended zones


## V0.5.0
- Flood date filtering uses Thai Buddhist year (B.E.) correctly.
- Route flood-risk heuristic compares current-day flooded station road names with OCR pickup/drop-off text.
- Risk levels: high / caution / no matching flooded station.
- No matching station is explicitly NOT treated as proof that the road is dry.
- The score is a station-name heuristic, not a true road-by-road route calculation.
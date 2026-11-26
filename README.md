# Haluvi Explorer

Haluvi Explorer is an experimental native Android application for blind and visually impaired people to explore the layout of mapped islands by virtual walking.

## Version 0.1.0

- Browse the Haluvi Navigation atoll and island catalog, or search by name, then download the selected island's OpenStreetMap road and place data. Atoll-based lookups are anchored to the catalog coordinates and reject buildings or shops as download areas.
- Import Haluvi Navigation v2 full-backup or shared-map JSON from Map Management. Google Places and other source records are kept on-device with their original provider fields; records without usable names or coordinates remain available in a separate review list.
- Continue exploring offline after a map has been saved on the phone.
- Follow road geometry through curved roads; downloaded maps use shared OpenStreetMap nodes, while imported backup junctions are labeled as estimates.
- Inspect road endpoints, road length, nearby mapped places, and the order and side of places along a road. Road/address hints are used when the source provides them; otherwise place-to-road matching uses geometry and reports its estimated distance, with no fixed distance cutoff that silently hides records.
- Move by configurable steps, places, junctions, or automatic walking. Automatic walking pauses at junctions by default.
- Review surroundings, virtual position, recent movement history, saved sessions, and bookmarks.
- Practice a destination, a return journey, and a road connection memory challenge.
- Use the application with TalkBack or Jieshuo+. Controls use native Android views and accessible text labels.

## Map source and limits

Island searches use OpenStreetMap's Nominatim service. A map is downloaded only after the user selects a search result. Road and place data are stored locally as JSON map files in the app's private files and are available offline after download. The app does not request GPS or location permission.

Map and place coverage depend on volunteer mapping. OSM downloads include road geometry and mapped place categories, including public transport, historical, natural and infrastructure features. A nearby place coordinate is not proof that an entrance faces a road. Online map updates request each way's actual OSM node list and geometry, then replace estimated OSM road sections while keeping saved places and user-defined roads. Imported backups and bundled atoll snapshots omit node IDs, so their remaining junctions are estimates from identical coordinate vertices; arbitrary line crossings are not treated as connections. The app does not provide real-world pedestrian safety directions.

Haluvi Navigation backup exports include per-island road geometry, place records and user road names. The importer preserves Google Places and other place sources locally on the device, including original address/provider fields that can help associate a place with a named road. Records with missing names or coordinates are preserved for review but cannot be assigned an exact virtual position. The backup format omits OpenStreetMap node IDs, so imported junctions are inferred from shared coordinate vertices and are marked as estimates in Explorer. Place lists are paginated so large islands do not create thousands of screen views at once.

Map data © OpenStreetMap contributors, available under the Open Database License (ODbL): <https://www.openstreetmap.org/copyright>.

## Build and release

Every push to `main` runs the Kotlin road-network and import tests, verifies the permanent signing certificate, builds the release APK, checks the APK certificate, and publishes a GitHub Release with the directly downloadable signed APK. The GitHub Actions secrets must contain `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, and `ANDROID_KEY_ALIAS`; the workflow pins the expected certificate fingerprint. Build numbers increase only when a release is published, so failed builds do not consume a number.

## Development

The project uses Kotlin, Android Gradle Plugin 8.7.3, Gradle 8.10.2, Android SDK 35, and Java 17. Run:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

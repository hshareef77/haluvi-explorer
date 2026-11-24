# Haluvi Explorer

Haluvi Explorer is an experimental native Android application for blind and visually impaired people to explore the layout of mapped islands by virtual walking.

## Version 0.1.0, build 1

- Search for a Maldives island or locality, then download its OpenStreetMap road and place data.
- Continue exploring offline after a map has been saved on the phone.
- Follow road geometry through curved roads; movement stops at road endpoints and presents only connections that share an OpenStreetMap road node.
- Inspect road endpoints, road length, nearby mapped places, and the approximate order and side of places along a road.
- Move by configurable steps, places, junctions, or automatic walking. Automatic walking pauses at junctions by default.
- Review surroundings, virtual position, recent movement history, saved sessions, and bookmarks.
- Practice a destination, a return journey, and a road connection memory challenge.
- Use the application with TalkBack or Jieshuo+. Controls use native Android views and accessible text labels.

## Map source and limits

Island searches use OpenStreetMap's Nominatim service. A map is downloaded only after the user selects a search result. Road and place data are stored locally as JSON map files in the app's private files and are available offline after download. The app does not request GPS or location permission.

Map and place coverage depend on volunteer mapping. A nearby place coordinate is not proof that an entrance faces a road. The app does not guess roads at line crossings; it requires a shared road-node identifier. It does not provide real-world pedestrian safety directions.

Map data © OpenStreetMap contributors, available under the Open Database License (ODbL): <https://www.openstreetmap.org/copyright>.

## Build and release

Every push to `main` runs the Kotlin geometry tests, verifies the permanent signing certificate, builds the release APK, checks the APK certificate, and publishes a GitHub Release with the directly downloadable signed APK. The GitHub Actions secrets must contain `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, and `ANDROID_KEY_ALIAS`; the workflow pins the expected certificate fingerprint. The build number comes from the workflow run number so future APKs can update earlier installs.

## Development

The project uses Kotlin, Android Gradle Plugin 8.7.3, Gradle 8.10.2, Android SDK 35, and Java 17. Run:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

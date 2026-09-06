# Build fixes

This revision fixes the Kotlin compilation errors reported by GitHub Actions:

- `Traffic` is no longer private while being used by public composable functions.
- Response HEX conversion now uses `ByteArray.copyOfRange(...)` instead of calling `toByteArray()` on the result of `take(...)`.
- HTTP header/body separator lookup now uses a ByteArray-safe `indexOfBytes(...)` helper instead of `ByteArray.indexOf(ByteArray)`.

The GitHub workflow already installs Gradle 8.11.1 and JDK 17.


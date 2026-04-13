# Milou - Game Scraper and Downloader

A modern Android app for discovering, downloading, and managing retro games. Named after my dog and done with love and lack of knowledge (I'm a ruby dev don't hate me).

## Download

Check the [releases](https://github.com/santiifm/milou/releases)

## How It Works

Milou scrapes game data from .torrent files or magnet links, provides search and filtering, and handles downloads with automatic archive extraction. The app comes prepackaged with some ROMs to quickly get started.

### Core Features
- **Search & Filter**: Find games by name, console, region, or content type
- **Download Management**: Background downloads with progress tracking
- **Auto-Extraction**: Automatically extracts ZIP/7z archives
- **Source Management**: Add/remove scraping sources for different sites
- **Landscape Support**: Optimized UI for both portrait and landscape modes

### App Flow
1. **First Launch**: App loads default sources and scrapes game data
2. **Search**: Use filters to find specific games
3. **Download**: Tap games to start downloading
4. **Manage**: Monitor progress in Downloads screen
5. **Configure**: Adjust settings like download folder and speed limits
6. **Console-specific Download Paths**: Go into Sources -> Manufacturer(e.g. Sony) -> Console(e.g. PS2) and clock the folder icon to set that console's download path

## Building

### Build Steps
```bash
# Clone repository
git clone https://github.com/yourusername/milou-test.git
cd milou-test

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test
```

### Project Structure
```
app/                    # Main Android application
├── src/main/java/     # Kotlin source code
├── src/main/assets/   # Default sources (consoles.json)
```

## Tech Stack
- **UI**: Jetpack Compose, Material Design 3
- **Database**: Room with SQLite, Full-Text Search
- **Dependency Injection**: Hilt
- **Networking**: Jsoup (scraping), HttpURLConnection (downloads)
- **Archives**: Apache Commons Compress (ZIP/7z)
- **Concurrency**: Kotlin Coroutines, Flow

## Usage

### First Time Setup
1. Open Settings and select download directory
2. App automatically loads sources and scrapes game data
3. Start searching and downloading games

### Adding Sources
1. Go to Sources screen
2. Add manufacturers and consoles
3. Add .torrent/magnet for scraping
4. Use "Rescan Sources" to update database or the refresh icon on a console to only reload that one

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any games.

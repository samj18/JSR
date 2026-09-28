# FileBridge v0.3

LAN file sharing app — Spring Boot + JavaFX in one process.

## What's new in v0.3

- 🕒 **Time-limited shares** — pick how long a folder is available: 15 min / 1 h / 4 h / 1 day / forever
- ⏰ **Auto-expire** — expired shares are removed automatically every 30 s; the list shows a live countdown
- 🔒 **Port is fixed** — read-only in the UI; change it once in `application.properties` if needed

## Run

1. Open in IntelliJ (**File ▸ Open** → select the `filebridge` folder)
2. Wait for Maven import
3. Run `app/FileBridgeLauncher.java`

## How to share a folder

1. Click **+ Add share…**
2. Pick a folder from your system
3. In the dialog:
   - Choose **duration** (15 min, 1 hour, 4 hours, 1 day, or "Never expires")
   - Optionally tick **"Allow peers to upload/delete"** to make it writable
4. Click OK

The share appears in the list with `⏱ time left`. When time runs out, it's removed automatically and any peer trying to access it gets HTTP 404.

## Port

The server port is fixed via `src/main/resources/application.properties`:

```properties
server.port=8080
```

To change it, edit that file and restart the app. The UI shows the port as a read-only label — you cannot change it from within the app.

## API endpoints

```
GET  /api/info
GET  /api/shares                  X-Pin header
GET  /api/shares/{id}/list        path param
GET  /api/shares/{id}/file        Range supported
POST /api/shares/{id}/upload      X-Filename, path
DELETE /api/shares/{id}/file      path param
```

All endpoints return 404 for expired shares.

## Project structure

```
src/main/java/com/filebridge/
├── app/        bootstrap
├── shared/model/  SharedFolder, FileEntry, NetworkInterfaceInfo, ShareDuration
├── server/
│   ├── controller/ ShareController
│   ├── service/    ShareService (with expiration sweeper), PinService, SettingsService
│   └── filter/     LanOnlyFilter
└── client/
    ├── controller/ MainController
    └── service/    PeerClient, NetworkUtils, LocalizationService, ThemeService
```

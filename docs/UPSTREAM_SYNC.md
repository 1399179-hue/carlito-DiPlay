# Upstream synchronization

The `Sync upstream` workflow checks `shihabal3amri/DiPlay`'s `main` branch once an hour, after pushes to this repository's `main`, and when started manually from **Actions → Sync upstream → Run workflow**. It merges upstream commits into this repository's `main` and starts the Android workflow when it advances.

If an upstream change conflicts with a DiPlay modification, the sync stops without pushing a partial merge. Resolve the conflict on `main`, then run the sync workflow again.

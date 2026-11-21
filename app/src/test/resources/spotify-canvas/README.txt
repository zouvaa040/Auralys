Fixtures for the Spotify Canvas tests (SpotifyCanvas*Test).

search-tracks.json, canvas-anonymous.json, web-player.json
  Real answers of Spotify's web player captured with an anonymous token by BitChord
  (https://github.com/kushagrasinghx/BitChord, GPL-3.0, web/fixtures/spotify/, 2026-10):
  `searchTracks` for "Let Down Radiohead" (10 tracks), `canvas` for "Espresso" (null when anonymous), and the
  hashes the web player bundle declared. No token or account data is in them.

canvas-found-example.json
  NOT a capture. A hand-written answer in the shape the parser reads (data.trackUnion.canvas.url, as BitChord's
  parser does); nobody could sign in to Spotify while this was built, so the real signed-in shape is unverified.

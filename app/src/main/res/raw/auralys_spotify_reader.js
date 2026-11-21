(function () {
  if (window.__aur && window.__aur.version === 2) return;
  var S = window.__aur = { version: 2, rows: {}, count: 0, total: 0, idle: 0, passes: 0, fine: false, stage: 'start' };

  function mainGrid() {
    // The playlist table is the first grid that holds track rows; "Recommended" comes after it.
    var grids = document.querySelectorAll('[role="grid"]');
    for (var i = 0; i < grids.length; i++) {
      if (grids[i].querySelector('[data-testid="tracklist-row"]')) return grids[i];
    }
    return null;
  }

  function declaredTotal() {
    var text = document.body ? document.body.innerText : '';
    var m = text.match(/(\d[\d.,   ]*)\s*(songs?|lagu|tracks?)\b/i);
    if (!m) return 0;
    var n = parseInt(m[1].replace(/[^\d]/g, ''), 10);
    return isNaN(n) ? 0 : n;
  }

  function scroller(grid) {
    var el = grid;
    while (el) {
      var ov = getComputedStyle(el).overflowY;
      if ((ov === 'scroll' || ov === 'auto') && el.scrollHeight > el.clientHeight + 100) return el;
      el = el.parentElement;
    }
    return null;
  }

  function readRows(grid) {
    var rows = grid.querySelectorAll('[data-testid="tracklist-row"]');
    var added = 0;
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var cells = r.querySelectorAll('[role="gridcell"]');
      var link = r.querySelector('a[data-testid="internal-track-link"]');
      if (!link || !cells.length) continue;
      // textContent, not innerText: on touch screens Spotify hides the number column with CSS, so innerText is empty.
      var n = parseInt((cells[0].textContent || '').trim(), 10);
      if (isNaN(n)) {
        var wrap = r.closest('[role="row"]');
        var ri = wrap ? parseInt(wrap.getAttribute('aria-rowindex'), 10) : NaN;
        if (!isNaN(ri)) n = ri - 1; // row 1 of the grid is the header
      }
      var title = (link.textContent || '').trim();
      if (isNaN(n) || !title || S.rows[n]) continue;
      var id = ((link.getAttribute('href') || '').match(/\/track\/([A-Za-z0-9]+)/) || [])[1] || '';
      var artists = [];
      var as = r.querySelectorAll('a[href*="/artist/"]');
      for (var j = 0; j < as.length; j++) {
        var name = (as[j].textContent || '').trim();
        if (name && artists.indexOf(name) < 0) artists.push(name);
      }
      var dur = (cells[cells.length - 1].textContent || '').trim();
      var parts = dur.match(/(?:(\d+):)?(\d+):(\d{2})\s*$/);
      var ms = parts ? ((parseInt(parts[1] || '0', 10) * 3600) + (parseInt(parts[2], 10) * 60) + parseInt(parts[3], 10)) * 1000 : 0;
      S.rows[n] = { n: n, id: id, title: title, artists: artists, ms: ms };
      S.count++;
      added++;
    }
    return added;
  }

  function step() {
    var grid = mainGrid();
    if (!grid) { S.stage = 'wait-grid'; return; }
    if (!S.total) S.total = declaredTotal();
    var added = readRows(grid);
    S.idle = added === 0 ? S.idle + 1 : 0;
    var sc = scroller(grid);
    if (S.total && S.count >= S.total) { S.stage = 'done'; return; }
    if (!sc) { S.stage = 'no-scroller'; return; }
    var atEnd = sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 4;
    if (atEnd && S.idle >= 3) {
      // Reached the bottom. If rows were skipped (fast scrolling), sweep again from the top in smaller steps.
      if (S.total && S.count < S.total && S.passes < 3) {
        S.passes++;
        S.fine = true;
        S.idle = 0;
        sc.scrollTop = 0;
        return;
      }
      S.stage = 'done';
      return;
    }
    S.stage = 'scrolling';
    sc.scrollTop += Math.max(150, Math.floor(sc.clientHeight * (S.fine ? 0.3 : 0.7)));
  }

  S.timer = setInterval(function () {
    try { step(); } catch (e) { S.error = String(e); }
    if (S.stage === 'done' || S.stage === 'no-scroller') clearInterval(S.timer);
  }, 250);
})();

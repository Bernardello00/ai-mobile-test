(() => {
  const abs = (u) => { try { return new URL(u, location.href).href; } catch { return ''; } };
  const bgUrl = (el) => {
    try {
      const bg = getComputedStyle(el).backgroundImage || '';
      const m = bg.match(/url\(["']?(.*?)["']?\)/i);
      return m ? abs(m[1]) : '';
    } catch { return ''; }
  };
  const seen = new Map();
  const collect = () => {
    const nodes = [...document.querySelectorAll('img,video,[style*="background"],a')];
    nodes.forEach((el, idx) => {
      const r = el.getBoundingClientRect();
      if (r.width < 100 || r.height < 70) return;
      const img = el.tagName === 'IMG' ? abs(el.currentSrc || el.src || '') : '';
      const poster = el.tagName === 'VIDEO' ? abs(el.poster || '') : '';
      const preview = el.tagName === 'VIDEO' ? abs(el.currentSrc || el.src || '') : '';
      const bg = bgUrl(el);
      const visual = img || poster || bg;
      if (!visual && !preview) return;
      let a = el.closest('a[href]');
      const href = a ? abs(a.href) : location.href;
      const key = href + '|' + visual + '|' + preview;
      if (!seen.has(key)) seen.set(key, {
        id: idx, href, imageUrl: visual, previewUrl: preview,
        width: Math.round(r.width), height: Math.round(r.height),
        x: Math.round(r.left), y: Math.round(r.top)
      });
    });
    return [...seen.values()];
  };

  const candidates = [...document.querySelectorAll('a,article,li,div')]
    .filter(el => { const r = el.getBoundingClientRect(); return r.width >= 160 && r.height >= 90 && r.width <= innerWidth * 1.1; })
    .slice(0, 180);

  collect();
  let i = 0;
  const step = () => {
    if (i >= candidates.length) {
      setTimeout(() => AndroidScanner.onScanResults(JSON.stringify({url: location.href, items: collect()})), 650);
      return;
    }
    const el = candidates[i++];
    try {
      ['mouseenter','mouseover','mousemove'].forEach(type => el.dispatchEvent(new MouseEvent(type, {bubbles:true, view:window})));
      el.scrollIntoView({block:'center', behavior:'instant'});
    } catch (_) {}
    setTimeout(() => { collect(); step(); }, 90);
  };
  step();
})();

(async () => {
  const SESSION = Number(window.__VVS_SESSION || 0);
  const PAGE = Number(window.__VVS_PAGE || 0);
  const MAX_CARDS = Math.max(10, Math.min(250, Number(window.__VVS_MAX_CARDS || 80)));
  const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
  const abs = (u) => { try { return u ? new URL(u, location.href).href : ''; } catch { return ''; } };
  const canonical = (u) => { try { const x = new URL(u, location.href); x.hash=''; return x.href.replace(/\/$/, ''); } catch { return ''; } };
  const visible = (el) => {
    try {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width >= 2 && r.height >= 2 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0.02;
    } catch { return false; }
  };
  const bgUrl = (el) => {
    try {
      const bg = getComputedStyle(el).backgroundImage || '';
      const m = bg.match(/url\(["']?(.*?)["']?\)/i);
      return m ? abs(m[1]) : '';
    } catch { return ''; }
  };
  const hrefFor = (el) => {
    const a = el.closest?.('a[href]') || el.querySelector?.('a[href]');
    return a ? abs(a.href) : location.href;
  };
  const visualFor = (el) => {
    const img = el.matches?.('img') ? el : el.querySelector?.('img');
    const video = el.matches?.('video') ? el : el.querySelector?.('video');
    let imageUrl = '';
    let previewUrl = '';
    if (img) imageUrl = abs(img.currentSrc || img.src || '');
    if (video) {
      imageUrl = abs(video.poster || '') || imageUrl;
      previewUrl = abs(video.currentSrc || video.src || video.querySelector?.('source')?.src || '');
    }
    if (!imageUrl) imageUrl = bgUrl(el);
    if (!imageUrl) {
      const bgChild = [...(el.querySelectorAll?.('*') || [])].slice(0, 16).find(x => bgUrl(x));
      if (bgChild) imageUrl = bgUrl(bgChild);
    }
    return { imageUrl, previewUrl };
  };

  const fnv = (seed) => {
    let h = 2166136261 >>> 0;
    for (let i=0;i<seed.length;i++) { h ^= seed.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; }
    return h.toString(16).padStart(8,'0');
  };

  const currentPageNumber = () => {
    const selectors = [
      '[aria-current="page"]', '.pagination .active', '.pager .active',
      '.page-numbers.current', '[class*="pagination"] [class*="active"]',
      '[class*="pager"] [class*="active"]'
    ];
    for (const s of selectors) {
      const el = document.querySelector(s);
      const n = Number((el?.textContent || '').trim());
      if (Number.isFinite(n) && n > 0) return n;
    }
    return null;
  };

  const pageIdentity = () => {
    const p = currentPageNumber() ?? '';
    const visuals = [...document.querySelectorAll('img,video')].slice(0, 24).map(el => {
      const v = visualFor(el);
      return `${hrefFor(el)}|${v.imageUrl}|${v.previewUrl}`;
    });
    return fnv(`${canonical(location.href)}|${p}|${visuals.join('||')}`);
  };
  window.__VVS_pageIdentity = pageIdentity;

  const waitForNativeCapacity = async () => {
    for (let i=0; i<80; i++) {
      try {
        if (!AndroidScanner || typeof AndroidScanner.getPendingInference !== 'function') return;
        if (Number(AndroidScanner.getPendingInference()) < 2) return;
      } catch (_) { return; }
      await sleep(120);
    }
  };

  const seen = new Map();
  const collect = () => {
    const nodes = [...document.querySelectorAll('img,video,[style*="background-image"],[style*="background"]')];
    for (const el of nodes) {
      let r;
      try { r = el.getBoundingClientRect(); } catch { continue; }
      if (r.width < 100 || r.height < 65) continue;
      const {imageUrl, previewUrl} = visualFor(el);
      if (!imageUrl && !previewUrl) continue;
      const href = hrefFor(el);
      const key = `${href}|${imageUrl}|${previewUrl}`;
      if (!seen.has(key)) seen.set(key, {
        id: key.slice(0, 220), href, sourceUrl: location.href,
        imageUrl, previewUrl,
        width: Math.round(r.width), height: Math.round(r.height)
      });
    }
    return [...seen.values()];
  };

  const scrollThroughPage = async () => {
    window.scrollTo({top: 0, behavior: 'instant'});
    await sleep(100);
    let stable = 0;
    let previousHeight = 0;
    for (let i = 0; i < 42; i++) {
      const h = Math.max(document.body?.scrollHeight || 0, document.documentElement?.scrollHeight || 0);
      const y = Math.min(Math.max(0, h - innerHeight), Math.round((i + 1) * innerHeight * 0.82));
      window.scrollTo({top: y, behavior: 'instant'});
      await sleep(130);
      const h2 = Math.max(document.body?.scrollHeight || 0, document.documentElement?.scrollHeight || 0);
      if (h2 === previousHeight && scrollY + innerHeight >= h2 - 8) stable++; else stable = 0;
      previousHeight = h2;
      collect();
      if (stable >= 3) break;
    }
    window.scrollTo({top: Math.max(0, (document.documentElement?.scrollHeight || 0) - innerHeight), behavior: 'instant'});
    await sleep(220);
  };

  const cardRoot = (visual) => {
    const linked = visual.closest?.('a[href]');
    if (linked) return linked;
    let node = visual;
    for (let depth = 0; depth < 5 && node?.parentElement; depth++, node = node.parentElement) {
      const p = node.parentElement;
      const r = p.getBoundingClientRect();
      if (r.width >= 140 && r.height >= 80 && r.width <= innerWidth * 1.08 && r.height <= innerHeight * 1.4) {
        if (p.matches('article,li,[role="listitem"],figure') || p.querySelector('a[href],video')) return p;
      }
    }
    return visual;
  };

  const discoverCards = () => {
    const visualNodes = [...document.querySelectorAll('img,video')].filter(el => {
      try { const r = el.getBoundingClientRect(); return r.width >= 120 && r.height >= 70; } catch { return false; }
    });
    const roots = [];
    const keys = new Set();
    for (const visual of visualNodes) {
      const root = cardRoot(visual);
      if (!root || !visible(root)) continue;
      const r = root.getBoundingClientRect();
      const href = hrefFor(root);
      const key = href !== location.href ? href : `${Math.round(r.left)}:${Math.round(r.top + scrollY)}:${Math.round(r.width)}:${Math.round(r.height)}`;
      if (keys.has(key)) continue;
      keys.add(key);
      roots.push(root);
    }
    roots.sort((a,b) => (a.getBoundingClientRect().top + scrollY) - (b.getBoundingClientRect().top + scrollY));
    return roots.slice(0, MAX_CARDS);
  };

  const emitFrame = async (el, id, stage) => {
    try {
      await waitForNativeCapacity();
      const r = el.getBoundingClientRect();
      if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) return;
      const {imageUrl, previewUrl} = visualFor(el);
      AndroidScanner.onVisualFrame(JSON.stringify({
        session: SESSION, page: PAGE, id, stage,
        sourceUrl: location.href, href: hrefFor(el), imageUrl, previewUrl,
        x: r.left, y: r.top, width: r.width, height: r.height,
        viewportWidth: innerWidth, viewportHeight: innerHeight
      }));
    } catch (_) {}
  };

  const hoverCards = async (cards) => {
    let index = 0;
    for (const el of cards) {
      const id = `p${PAGE}-c${index++}`;
      try {
        el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'instant'});
        await sleep(90);
        ['pointerenter','mouseenter','mouseover','mousemove'].forEach(type => {
          const Ctor = type.startsWith('pointer') && window.PointerEvent ? PointerEvent : MouseEvent;
          el.dispatchEvent(new Ctor(type, {bubbles:true, cancelable:true, view:window, pointerType:'mouse'}));
        });
      } catch (_) {}
      await sleep(320);
      collect();
      await emitFrame(el, id, 'hover-1');
      let hasPreview = false;
      try {
        hasPreview = !!el.querySelector('video') || [...document.querySelectorAll('video')].some(v => {
          const a=v.getBoundingClientRect(), b=el.getBoundingClientRect();
          return a.right>b.left && a.left<b.right && a.bottom>b.top && a.top<b.bottom;
        });
      } catch (_) {}
      if (hasPreview) {
        await sleep(420);
        collect();
        await emitFrame(el, id, 'hover-2');
      }
      try {
        ['mouseout','mouseleave','pointerleave'].forEach(type => {
          const Ctor = type.startsWith('pointer') && window.PointerEvent ? PointerEvent : MouseEvent;
          el.dispatchEvent(new Ctor(type, {bubbles:true, cancelable:true, view:window, pointerType:'mouse'}));
        });
      } catch (_) {}
      await sleep(60);
    }
  };

  const paginationRoots = () => {
    const selectors = [
      'nav[aria-label*="pagination" i]', '.pagination', '[class*="pagination"]',
      '.pager', '[class*="pager"]', '.page-numbers', '[class*="page-nav"]',
      '[class*="paginator"]'
    ];
    const roots = [];
    const seenRoots = new Set();
    for (const s of selectors) {
      for (const el of document.querySelectorAll(s)) {
        if (!visible(el) || seenRoots.has(el)) continue;
        seenRoots.add(el); roots.push(el);
      }
    }
    return roots;
  };

  const findNext = () => {
    document.querySelectorAll('[data-vvs-next="1"]').forEach(el => el.removeAttribute('data-vvs-next'));
    const roots = paginationRoots();
    const rootSet = new Set(roots);
    const inPagination = (el) => roots.some(r => r === el || r.contains(el));
    const controls = [...document.querySelectorAll('a[href],button,[role="button"]')].filter(el => {
      if (!visible(el)) return false;
      if (el.matches('[disabled],[aria-disabled="true"]')) return false;
      const rel = (el.getAttribute('rel') || '').toLowerCase().split(/\s+/);
      const txt = `${el.textContent || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('title') || ''}`.toLowerCase();
      return inPagination(el) || rel.includes('next') || /next|successiv|seguent|avanti|older/.test(txt);
    });
    const pageNo = currentPageNumber();
    const docHeight = Math.max(document.body?.scrollHeight || 1, document.documentElement?.scrollHeight || 1);
    let best = null;
    let bestScore = -1e9;
    let bestLabel = '';
    for (const el of controls) {
      const raw = `${el.textContent || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('title') || ''}`.trim();
      const text = raw.toLowerCase().replace(/\s+/g,' ').trim();
      const rel = (el.getAttribute('rel') || '').toLowerCase();
      const paged = inPagination(el);
      const href = el.tagName === 'A' ? abs(el.href) : '';
      const r = el.getBoundingClientRect();
      const absoluteTop = r.top + scrollY;
      let score = 0;
      if (rel.split(/\s+/).includes('next')) score += 2200;
      if (/^(next|next page|avanti|successiva|successivo|seguente|pagina successiva|older|più vecchi)$/.test(text)) score += 1700;
      if (/next|successiv|seguent|avanti/.test(text)) score += 900;
      if (paged) score += 400;
      if (paged && /^(›|»|→|>)$/.test(text)) score += 1200;
      if (absoluteTop > docHeight * 0.6) score += 120;
      if (href) {
        try { if (new URL(href).origin === location.origin) score += 100; else score -= 500; } catch { score -= 500; }
        if (canonical(href) === canonical(location.href)) score -= 700;
      }
      const numeric = /^\d+$/.test(text) ? Number(text) : null;
      if (numeric != null) {
        if (!paged) score -= 500;
        if (pageNo != null) {
          if (numeric === pageNo + 1) score += 1600;
          else if (numeric <= pageNo) score -= 1200;
          else score += Math.max(0, 250 - (numeric - pageNo) * 25);
        } else {
          score += numeric === 2 ? 450 : 0;
        }
      }
      if (text.length > 80) score -= 250;
      if (score > bestScore) { bestScore = score; best = el; bestLabel = raw || text; }
    }
    if (!best || bestScore < 700) return {nextUrl:'', hasNextClick:false, nextLabel:''};
    best.setAttribute('data-vvs-next','1');
    const href = best.tagName === 'A' ? abs(best.href) : '';
    if (href && canonical(href) !== canonical(location.href)) return {nextUrl:href, hasNextClick:true, nextLabel:bestLabel};
    return {nextUrl:'', hasNextClick:true, nextLabel:bestLabel};
  };

  const fingerprint = (items) => {
    const seed = `${location.pathname}|${items.slice(0,40).map(x => `${x.href}|${x.imageUrl}`).join('||')}`;
    return fnv(seed);
  };

  window.__VVS_clickNextAndProbe = (beforeIdentity) => {
    try {
      const e = document.querySelector('[data-vvs-next="1"]');
      if (!e) return false;
      const beforeUrl = canonical(location.href);
      e.click();
      let tries = 0;
      const probe = () => {
        try {
          const nowUrl = canonical(location.href);
          const nowIdentity = pageIdentity();
          const changed = nowUrl !== beforeUrl || nowIdentity !== beforeIdentity;
          if (changed) {
            AndroidScanner.onNavigationChanged(JSON.stringify({
              session: SESSION,
              url: location.href,
              identity: nowIdentity,
              pageNumber: currentPageNumber() == null ? '' : String(currentPageNumber())
            }));
            return;
          }
          tries++;
          if (tries < 32) setTimeout(probe, 250);
          else AndroidScanner.onNavigationFailed(JSON.stringify({session: SESSION, url: location.href, identity: nowIdentity}));
        } catch (_) {
          tries++;
          if (tries < 32) setTimeout(probe, 250);
          else AndroidScanner.onNavigationFailed(JSON.stringify({session: SESSION, url: location.href}));
        }
      };
      setTimeout(probe, 300);
      return true;
    } catch (_) { return false; }
  };

  try {
    collect();
    await scrollThroughPage();
    const cards = discoverCards();
    await hoverCards(cards);
    await waitForNativeCapacity();
    await sleep(180);
    const items = collect();
    window.scrollTo({top: Math.max(0, (document.documentElement?.scrollHeight || 0) - innerHeight), behavior:'instant'});
    await sleep(140);
    const next = findNext();
    const identity = pageIdentity();
    const pageNo = currentPageNumber();
    AndroidScanner.onScanResults(JSON.stringify({
      session: SESSION, page: PAGE, url: location.href, title: document.title,
      fingerprint: fingerprint(items), identity,
      currentPage: pageNo == null ? '' : String(pageNo),
      cardsHovered: cards.length, items,
      nextUrl: next.nextUrl, hasNextClick: next.hasNextClick, nextLabel: next.nextLabel
    }));
  } catch (error) {
    AndroidScanner.onScanResults(JSON.stringify({
      session: SESSION, page: PAGE, url: location.href,
      fingerprint: `error-${PAGE}-${Date.now()}`, identity: pageIdentity(), items: collect(),
      nextUrl: '', hasNextClick: false, nextLabel:'',
      error: String(error && error.message || error)
    }));
  }
})();

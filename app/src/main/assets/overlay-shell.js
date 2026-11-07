/*
 * Live overlay shell for preset/session scripts (spike, opt-in per session).
 *
 * The Android host loads this document into a full-screen transparent WebView. Nothing is
 * clipped, translated or resized here: the shell document is laid out at the logical screen size
 * and the host receives a list of interactive rectangles ("touch proxies"). It builds one native
 * transparent proxy per rect, so touches inside those rects reach this WebView and everything
 * else falls through to the chat underneath.
 *
 * The real script runs inside an iframe (same runtime as the hidden session host). Because the
 * iframe's parent is this shell, `parent.document` in preset code points here, and appending a
 * fixed node to `parent.document.body` actually renders and can be dragged/clicked.
 */
(() => {
  if (window.__xujingLiveShell) return;
  window.__xujingLiveShell = true;

  const boot = window.__XUJING_LIVE_BOOT__ || {};
  const host = window.XujingLive;

  const state = {
    sessionId: String(boot.sessionId || ''),
    scriptId: String(boot.scriptId || ''),
    scriptName: String(boot.scriptName || '预设脚本'),
    scriptContent: String(boot.scriptContent || ''),
    screen: {
      w: Math.max(1, Math.round(Number(boot.screenWidth) || window.innerWidth || 360)),
      h: Math.max(1, Math.round(Number(boot.screenHeight) || window.innerHeight || 640)),
    },
    snapshot: boot.snapshot || {},
    initialized: false,
    frame: null,
    requestSeq: 0,
    pending: new Map(),
    events: [],
    buttons: [],
    touchJson: '',
    dragging: false,
    warnedTouch: false,
  };

  const root = document.documentElement;
  const safe = value => JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
  const mark = node => {
    if (node && node.setAttribute) node.setAttribute('data-xujing-shell', '1');
    return node;
  };

  /* ------------------------------------------------------------------ theme */

  const applyTheme = theme => {
    const value = theme && typeof theme === 'object' ? theme : {};
    root.style.colorScheme = value.dark === false ? 'light' : 'dark';
    root.style.setProperty('--xujing-screen-w', state.screen.w + 'px');
    root.style.setProperty('--xujing-screen-h', state.screen.h + 'px');
    root.style.setProperty('--SmartThemeBodyColor', value.foreground || '#e6e1e5');
    root.style.setProperty('--SmartThemeEmColor', value.muted || '#cac4d0');
    root.style.setProperty('--SmartThemeQuoteColor', value.accent || '#8ab4f8');
    root.style.setProperty('--SmartThemeBlurTintColor', value.background || '#1c1b1f');
    root.style.setProperty('--SmartThemeBorderColor', value.border || '#49454f');
    root.style.setProperty('--SmartThemeFontSize', '13px');
  };

  const style = document.createElement('style');
  style.textContent = `
:root{--xujing-screen-w:360px;--xujing-screen-h:640px}
html{width:var(--xujing-screen-w);height:var(--xujing-screen-h);margin:0;padding:0;overflow:hidden;background:transparent}
body{position:absolute;left:0;top:0;width:var(--xujing-screen-w);height:var(--xujing-screen-h);margin:0;padding:0;overflow:visible;background:transparent;color:var(--SmartThemeBodyColor);font:var(--SmartThemeFontSize)/1.5 -apple-system,"Noto Sans SC",sans-serif}
#xujing-live-shims{position:fixed;left:-10000px;top:-10000px;width:1px;height:1px;overflow:hidden;pointer-events:none}
#xujing-live-frame{position:absolute;left:0;top:0;width:100%;height:100%;border:0;opacity:0;pointer-events:none}
`;
  mark(style);
  document.head.appendChild(style);
  applyTheme(boot.theme);

  // The Android WebView can be resized by the host at any time. Presets often read the parent
  // viewport for positioning, so report the logical screen instead of the backing view size.
  try {
    Object.defineProperty(window, 'innerWidth', { configurable: true, get: () => state.screen.w });
    Object.defineProperty(window, 'innerHeight', { configurable: true, get: () => state.screen.h });
    Object.defineProperty(document.documentElement, 'clientWidth', { configurable: true, get: () => state.screen.w });
    Object.defineProperty(document.documentElement, 'clientHeight', { configurable: true, get: () => state.screen.h });
  } catch (error) { console.error(error); }

  /* ------------------------------------------------------- app DOM shims */
  // Containers the presets usually reach through `parent.document`. They are marked as shell
  // nodes so the touch-publisher never treats them as script UI.
  const shims = mark(document.createElement('div'));
  shims.id = 'xujing-live-shims';
  document.body.appendChild(shims);

  const textarea = mark(document.createElement('textarea'));
  textarea.id = 'send_textarea';
  textarea.rows = 1;
  shims.appendChild(textarea);

  const mesText = mark(document.createElement('div'));
  mesText.className = 'mes_text';
  shims.appendChild(mesText);

  const promptList = mark(document.createElement('div'));
  promptList.id = 'completion_prompt_manager_list';
  shims.appendChild(promptList);

  const renderMessageShim = () => {
    const chat = Array.isArray(state.snapshot.chat) ? state.snapshot.chat : [];
    const last = chat.length ? chat[chat.length - 1] : null;
    mesText.innerHTML = last ? String(last.content ?? last.message ?? '') : '';
  };
  renderMessageShim();

  const input = () => document.getElementById('send_textarea');
  const writeInput = text => {
    const el = input();
    el.value = String(text ?? '');
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return el.value;
  };
  const setInput = text => writeInput(text);
  // Mirror direct `.value = ...` / dispatchEvent writes the same way the message shell does.
  textarea.addEventListener('input', () => {
    try { request('setInput', { text: textarea.value }).catch(() => {}); }
    catch (error) { console.error(error); }
  });

  const request = (method, payload) => new Promise((resolve, reject) => {
    const requestId = 'live-' + (++state.requestSeq);
    state.pending.set(requestId, { resolve, reject });
    try {
      window.XujingBridge.request(state.scriptId, requestId, method, JSON.stringify(payload || {}));
    } catch (error) {
      state.pending.delete(requestId);
      reject(error);
    }
  });

  window.__xujingReplyForFrame = (runtimeToken, requestId, result) => {
    const key = String(requestId);
    const task = state.pending.get(key);
    if (task) {
      state.pending.delete(key);
      if (result && typeof result === 'object' && result.error) task.reject(new Error(String(result.error)));
      else task.resolve(result);
      return;
    }
    try { state.frame?.contentWindow?.__xujingReply?.(requestId, result); }
    catch (error) { console.error(error); }
  };

  window.triggerSlash = async command => {
    const raw = String(command ?? '').trim();
    if (raw === '/send' || raw.startsWith('/send ')) {
      const text = raw.slice(6);
      writeInput(text);
      return request('send', { text });
    }
    if (raw === '/setinput' || raw.startsWith('/setinput ')) {
      return setInput(raw.slice(10));
    }
    throw new Error('暂不支持的斜杠命令：' + raw);
  };

  // A few presets reach for the parent SillyTavern facade. Keep it read-mostly.
  window.SillyTavern = {
    getContext: () => ({
      chat: Array.isArray(state.snapshot.chat) ? state.snapshot.chat : [],
      name1: state.snapshot.name1 || '我',
      name2: state.snapshot.name2 || '',
      chatId: state.snapshot.sessionId || state.sessionId,
      chatMetadata: { variables: state.snapshot.variables || {} },
      triggerSlash: window.triggerSlash,
      saveChat: async () => true,
      saveMetadata: async () => true,
      eventSource: { on: () => {}, once: () => {}, removeListener: () => {}, emit: async () => {} },
    }),
  };

  /* ------------------------------------------------------------ iframe page */

  const scriptTag = source => '<scr' + 'ipt src="' + source + '"></scr' + 'ipt>';
  const inlineScript = source => '<scr' + 'ipt>' + source + '</scr' + 'ipt>';

  const pageFor = () => {
    const runtimeContext = { ...state.snapshot, runtimeToken: state.scriptId };
    const metadata = {
      id: state.scriptId,
      name: state.scriptName,
      info: '',
      buttons: Array.isArray(boot.scriptButtons) ? JSON.parse(JSON.stringify(boot.scriptButtons)) : [],
    };
    return '<!doctype html><html><head><meta charset="utf-8"><base href="https://cards.xujing.invalid/">' +
      inlineScript(`window.$=window.jQuery=function(value){return{0:value,length:1,off:function(){return this},on:function(){return this},find:function(){return this},each:function(){return this}}};window.toastr=window.toastr||{info:function(){},success:function(){},error:function(){},warning:function(){}};window.errorCatched=function(fn){try{var r=fn();if(r&&typeof r.catch==='function')r.catch(function(e){console.error(e&&e.stack||e)});return r}catch(e){console.error(e&&e.stack||e)}};window.__XUJING_CONTEXT__=${safe(runtimeContext)};window.__XUJING_SCRIPT__=${safe(metadata)};`) +
      scriptTag('/runtime/zod.js') + scriptTag('/runtime/ejs.js') +
      scriptTag('/runtime/card-runtime.js') + scriptTag('/runtime/mvu.js') +
      scriptTag('/runtime/template.js') + scriptTag('/runtime/session-script-runtime.js') +
      '</head><body></body></html>';
  };

  const patchChild = child => {
    try {
      const slash = command => window.triggerSlash(command);
      if (child.TavernHelper) child.TavernHelper.triggerSlash = slash;
      // card-runtime also mirrors helpers onto window; replace its restricted /send-only version.
      if (typeof child.triggerSlash === 'function') child.triggerSlash = slash;
    } catch (error) { console.error(error); }
  };

  const frame = mark(document.createElement('iframe'));
  frame.id = 'xujing-live-frame';
  frame.setAttribute('aria-hidden', 'true');
  frame.tabIndex = -1;
  frame.style.width = state.screen.w + 'px';
  frame.style.height = state.screen.h + 'px';
  state.frame = frame;
  frame.onload = async () => {
    const child = frame.contentWindow;
    try {
      if (typeof child?.__xujingLoadSessionScript !== 'function') throw new Error('脚本运行时未加载');
      child.__xujingUpdate?.({ ...state.snapshot, runtimeToken: state.scriptId });
      await child.__xujingLoadSessionScript(state.scriptContent);
      patchChild(child);
    } catch (error) {
      console.error('live overlay script failed', error);
    } finally {
      state.initialized = true;
      flush();
      publishTouchRects(true);
    }
  };
  frame.srcdoc = pageFor();
  document.body.appendChild(frame);

  /* --------------------------------------------------------- touch proxies */

  // The host can only forward touches inside one of these rects, so the list has to describe the
  // script's real interactive surface: top-level fixed roots (orbs, menus, popovers) plus stray
  // interactive nodes that are laid out in normal flow. Everything the script paints on top of the
  // chat must be listed; everything listed steals touches from the chat, so keep it tight.
  const TOUCH_DEBOUNCE_MS = 100;
  const DRAG_DEBOUNCE_MS = 80;
  const MAX_TOUCH_RECTS = 128;
  const TOUCH_SELECTOR = 'button,a,input,select,textarea,[role="button"],[onclick],[data-xujing-touch]';
  const SKIP_TAGS = new Set([
    'HTML', 'BODY', 'HEAD', 'SCRIPT', 'STYLE', 'LINK', 'META', 'TEMPLATE', 'NOSCRIPT',
    'BASE', 'TITLE', 'IFRAME',
  ]);

  // Ids must stay stable across publishes even when siblings are inserted, so remember the base
  // id per element instead of using a live DOM index.
  const baseIds = new WeakMap();
  let baseIdSeq = 0;
  const baseIdFor = el => {
    let id = baseIds.get(el);
    if (!id) {
      id = el.id || ('n' + (++baseIdSeq));
      baseIds.set(el, id);
    }
    return id;
  };

  const numberOf = value => {
    const n = Number(value);
    return Number.isFinite(n) ? n : 1;
  };

  const isHidden = cs => {
    if (!cs) return true;
    if (cs.display === 'none') return true;
    if (cs.visibility === 'hidden' || cs.visibility === 'collapse') return true;
    if (numberOf(cs.opacity) <= 0.01) return true;
    if (cs.pointerEvents === 'none') return true;
    return false;
  };

  const isShellNode = el => !!(el && el.closest && el.closest('[data-xujing-shell]'));

  const insideAny = (el, set) => {
    for (let parent = el.parentElement; parent; parent = parent.parentElement) {
      if (set.has(parent)) return true;
    }
    return false;
  };

  const matchesTouch = el => {
    try { return !!el.matches(TOUCH_SELECTOR); }
    catch (error) { return false; }
  };

  // Screen-relative, clipped, integer CSS pixels. Rect coordinates are already relative to the
  // shell's top-left corner because the shell document is never translated.
  const pushTouchRect = (out, prefix, el, rect) => {
    const sw = state.screen.w;
    const sh = state.screen.h;
    const left = Math.max(0, Math.min(sw, rect.left));
    const top = Math.max(0, Math.min(sh, rect.top));
    const right = Math.max(0, Math.min(sw, rect.right));
    const bottom = Math.max(0, Math.min(sh, rect.bottom));
    const x = Math.round(left);
    const y = Math.round(top);
    const w = Math.round(right) - x;
    const h = Math.round(bottom) - y;
    if (w < 2 || h < 2) return;
    out.push({ id: prefix + ':' + baseIdFor(el), x, y, w, h });
  };

  const resizeObserved = new Set();
  const resizeObserver = typeof ResizeObserver === 'function'
    ? new ResizeObserver(() => scheduleTouch())
    : null;
  const refreshResizeTargets = targets => {
    if (!resizeObserver) return;
    for (const el of Array.from(resizeObserved)) {
      if (targets.has(el)) continue;
      try { resizeObserver.unobserve(el); } catch (error) { console.error(error); }
      resizeObserved.delete(el);
    }
    for (const el of targets) {
      if (!el || resizeObserved.has(el)) continue;
      try { resizeObserver.observe(el); resizeObserved.add(el); } catch (error) { console.error(error); }
    }
  };

  const publishTouchRects = force => {
    if (!document.body) return;
    const out = [];
    const fixedRoots = new Set();
    const resizeTargets = new Set([document.body]);
    const all = Array.from(document.documentElement.querySelectorAll('*'));

    // Pass 1: outer fixed roots. A nested fixed node inside a root needs no rect of its own; the
    // root's proxy already covers it and the WebView hit-tests the children itself.
    for (const el of all) {
      if (out.length >= MAX_TOUCH_RECTS) break;
      if (!el || el.nodeType !== 1) continue;
      if (SKIP_TAGS.has(el.tagName)) continue;
      if (isShellNode(el)) continue;
      const cs = window.getComputedStyle(el);
      if (!cs || cs.position !== 'fixed') continue;
      // A `pointer-events:none` fixed container is not a touch target, but its children may opt
      // back in with `pointer-events:auto`; leave them to pass 2.
      if (isHidden(cs)) continue;
      if (insideAny(el, fixedRoots)) continue;
      const rect = el.getBoundingClientRect();
      if (rect.width < 1 || rect.height < 1) continue;
      fixedRoots.add(el);
      resizeTargets.add(el);
      pushTouchRect(out, 'fixed', el, rect);
    }

    // Pass 2: stray interactive nodes outside those roots (inline input bars, normal-flow links…).
    for (const el of all) {
      if (out.length >= MAX_TOUCH_RECTS) break;
      if (!el || el.nodeType !== 1) continue;
      if (SKIP_TAGS.has(el.tagName)) continue;
      if (isShellNode(el)) continue;
      if (insideAny(el, fixedRoots)) continue;
      const cs = window.getComputedStyle(el);
      if (!cs || cs.position === 'fixed') continue;
      if (isHidden(cs)) continue;
      const selected = matchesTouch(el);
      const pointer = cs.cursor === 'pointer';
      if (!selected && !pointer) continue;
      // `cursor` is inherited: a clickable card would otherwise emit one rect per descendant.
      // The container's rect already covers them and the WebView hit-tests inside it.
      if (!selected) {
        const parent = el.parentElement;
        if (parent && window.getComputedStyle(parent).cursor === 'pointer') continue;
      }
      const rect = el.getBoundingClientRect();
      if (rect.width < 1 || rect.height < 1) continue;
      pushTouchRect(out, 'el', el, rect);
    }

    const json = JSON.stringify(out);
    refreshResizeTargets(resizeTargets);
    if (!force && json === state.touchJson) return;
    state.touchJson = json;
    if (host && typeof host.touchRects === 'function') {
      try { host.touchRects(json); }
      catch (error) { console.error('touchRects failed', error); }
    } else if (!state.warnedTouch) {
      state.warnedTouch = true;
      console.warn('XujingLive.touchRects 不可用：overlay 没有可交互区域');
    }
  };

  // Throttle instead of a trailing debounce: while a node is dragged the publish must still run
  // about every 100ms, otherwise the proxy would freeze until the drag stops.
  let touchTimer = 0;
  let forceNext = false;
  const scheduleTouch = (delay, force) => {
    if (force) forceNext = true;
    if (touchTimer) return;
    const wait = Number.isFinite(delay) ? Math.max(0, delay) : TOUCH_DEBOUNCE_MS;
    touchTimer = setTimeout(() => {
      touchTimer = 0;
      const forced = forceNext;
      forceNext = false;
      publishTouchRects(forced);
    }, wait);
  };

  const touchObserver = typeof MutationObserver === 'function'
    ? new MutationObserver(() => scheduleTouch())
    : null;
  if (touchObserver) {
    touchObserver.observe(document.documentElement, {
      subtree: true, childList: true, attributes: true,
    });
  }

  const onPointerActivity = event => {
    if (!event) return;
    if (event.type === 'pointerdown') state.dragging = true;
    else if (event.type === 'pointerup' || event.type === 'pointercancel') state.dragging = false;
    scheduleTouch(state.dragging ? DRAG_DEBOUNCE_MS : TOUCH_DEBOUNCE_MS);
  };
  for (const type of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'click']) {
    window.addEventListener(type, onPointerActivity, { passive: true, capture: true });
  }
  for (const type of ['scroll', 'transitionrun', 'transitionend', 'animationstart', 'animationend', 'animationiteration']) {
    window.addEventListener(type, () => scheduleTouch(), { passive: true, capture: true });
  }
  window.addEventListener('resize', () => scheduleTouch(0), { passive: true });

  /* -------------------------------------------------------------- host API */

  const flush = () => {
    const child = state.frame?.contentWindow;
    const events = state.events.splice(0);
    for (const item of events) {
      try { child?.__xujingSessionEvent?.(item.event, item.args); }
      catch (error) { console.error(error); }
    }
    const buttons = state.buttons.splice(0);
    for (const item of buttons) {
      try { child?.__xujingRunButton?.(item.name); }
      catch (error) { console.error(error); }
    }
  };

  window.__xujingLiveUpdate = next => {
    state.snapshot = next || {};
    renderMessageShim();
    try { state.frame?.contentWindow?.__xujingUpdate?.({ ...state.snapshot, runtimeToken: state.scriptId }); }
    catch (error) { console.error(error); }
    scheduleTouch();
  };

  window.__xujingLiveTheme = next => applyTheme(next);

  window.__xujingLiveSize = (width, height) => {
    state.screen.w = Math.max(1, Math.round(Number(width) || state.screen.w));
    state.screen.h = Math.max(1, Math.round(Number(height) || state.screen.h));
    root.style.setProperty('--xujing-screen-w', state.screen.w + 'px');
    root.style.setProperty('--xujing-screen-h', state.screen.h + 'px');
    if (state.frame) {
      state.frame.style.width = state.screen.w + 'px';
      state.frame.style.height = state.screen.h + 'px';
    }
    scheduleTouch(0);
  };

  window.__xujingLiveDispatch = async (event, payload, excludeToken) => {
    if (String(excludeToken || '') === state.scriptId) return null;
    const multiple = Array.isArray(payload);
    const args = multiple ? payload : [payload];
    if (!state.initialized) {
      state.events.push({ event, args });
      return null;
    }
    const child = state.frame?.contentWindow;
    if (typeof child?.__xujingSessionEvent !== 'function') return null;
    try { return await child.__xujingSessionEvent(event, args); }
    catch (error) { console.error(`live overlay event ${event} failed`, error); return null; }
  };

  window.__xujingLiveButton = async (scriptId, name) => {
    if (String(scriptId) !== state.scriptId) return false;
    if (!state.initialized) {
      state.buttons.push({ name });
      return true;
    }
    const child = state.frame?.contentWindow;
    if (typeof child?.__xujingRunButton !== 'function') return false;
    try { return await child.__xujingRunButton(String(name)); }
    catch (error) { console.error(error); return false; }
  };

  window.__xujingLiveShutdown = () => {
    state.pending.clear();
    state.events.length = 0;
    state.buttons.length = 0;
    if (touchTimer) { clearTimeout(touchTimer); touchTimer = 0; }
    try { touchObserver?.disconnect(); } catch (error) { console.error(error); }
    try { resizeObserver?.disconnect(); resizeObserved.clear(); } catch (error) { console.error(error); }
    try { state.frame?.contentWindow?.dispatchEvent?.(new Event('pagehide')); }
    catch (error) { console.error(error); }
  };

  publishTouchRects(true);
})();

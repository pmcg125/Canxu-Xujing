/*
 * Live overlay shell for preset/session scripts (spike, opt-in per session).
 *
 * The Android host loads this document into a small transparent WebView. The document itself is
 * laid out at the full screen size and translated so scripts that use `position:fixed` keep their
 * natural screen coordinates even though the WebView only covers the union of their nodes.
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
  const PAD = 6;

  const state = {
    sessionId: String(boot.sessionId || ''),
    scriptId: String(boot.scriptId || ''),
    scriptName: String(boot.scriptName || '预设脚本'),
    scriptContent: String(boot.scriptContent || ''),
    screen: {
      w: Math.max(1, Math.round(Number(boot.screenWidth) || window.innerWidth || 360)),
      h: Math.max(1, Math.round(Number(boot.screenHeight) || window.innerHeight || 640)),
    },
    offset: { x: 0, y: 0 },
    last: null,
    snapshot: boot.snapshot || {},
    initialized: false,
    frame: null,
    requestSeq: 0,
    pending: new Map(),
    events: [],
    buttons: [],
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
html{width:var(--xujing-screen-w);height:var(--xujing-screen-h);margin:0;padding:0;overflow:hidden;background:transparent;transform-origin:0 0;transform:translate(0,0)}
body{position:absolute;left:0;top:0;width:var(--xujing-screen-w);height:var(--xujing-screen-h);margin:0;padding:0;overflow:visible;background:transparent;transform-origin:0 0;transform:none;color:var(--SmartThemeBodyColor);font:var(--SmartThemeFontSize)/1.5 -apple-system,"Noto Sans SC",sans-serif}
#xujing-live-shims{position:fixed;left:-10000px;top:-10000px;width:1px;height:1px;overflow:hidden;pointer-events:none}
#xujing-live-frame{position:absolute;left:0;top:0;border:0;opacity:0;pointer-events:none}
#xujing-live-status{position:fixed;left:8px;bottom:8px;padding:2px 6px;border-radius:6px;font-size:11px;opacity:.55;background:var(--SmartThemeBlurTintColor);color:var(--SmartThemeEmColor);pointer-events:none}
`;
  mark(style);
  document.head.appendChild(style);
  applyTheme(boot.theme);

  // The Android WebView can be 1x1 while a script is still building its UI. Presets often read
  // the parent viewport for positioning, so report the logical screen instead of the clip window.
  try {
    Object.defineProperty(window, 'innerWidth', { configurable: true, get: () => state.screen.w });
    Object.defineProperty(window, 'innerHeight', { configurable: true, get: () => state.screen.h });
    Object.defineProperty(document.documentElement, 'clientWidth', { configurable: true, get: () => state.screen.w });
    Object.defineProperty(document.documentElement, 'clientHeight', { configurable: true, get: () => state.screen.h });
  } catch (error) { console.error(error); }

  /* ------------------------------------------------------- app DOM shims */
  // Containers the presets usually reach through `parent.document`. They are marked as shell
  // nodes so the geometry observer never treats them as script UI.
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
      measure(true);
    }
  };
  frame.srcdoc = pageFor();
  document.body.appendChild(frame);

  /* --------------------------------------------------------- geometry sync */

  const measure = force => {
    const body = document.body;
    if (!body) return;
    const nodes = [];
    const consider = el => {
      if (!el || el.nodeType !== 1) return;
      if (el.hasAttribute('data-xujing-shell')) return;
      const tag = el.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK' || tag === 'META' || tag === 'TEMPLATE' || tag === 'NOSCRIPT') return;
      const computed = window.getComputedStyle(el);
      if (computed.display === 'none' || computed.visibility === 'hidden' || computed.opacity === '0') return;
      const r = el.getBoundingClientRect();
      if (r.width < 0.5 || r.height < 0.5) return;
      nodes.push(r);
    };
    for (const el of Array.from(body.children)) consider(el);
    // The orb preset appends its root to documentElement, not body. Include those siblings too.
    for (const el of Array.from(document.documentElement.children)) {
      if (el === document.head || el === document.body) continue;
      consider(el);
    }
    for (const el of Array.from(document.documentElement.querySelectorAll('*'))) {
      if (window.getComputedStyle(el).position === 'fixed') consider(el);
    }
    if (!nodes.length) {
      state.last = null;
      if (state.initialized && host) host.empty();
      return;
    }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const r of nodes) {
      const x = r.left + state.offset.x;
      const y = r.top + state.offset.y;
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x + r.width > maxX) maxX = x + r.width;
      if (y + r.height > maxY) maxY = y + r.height;
    }
    const x = Math.max(0, Math.round(minX - PAD));
    const y = Math.max(0, Math.round(minY - PAD));
    const w = Math.min(state.screen.w - x, Math.round(maxX - minX + PAD * 2));
    const h = Math.min(state.screen.h - y, Math.round(maxY - minY + PAD * 2));
    if (w < 1 || h < 1) return;
    const next = { x, y, w, h };
    const last = state.last;
    const same = last && Math.abs(last.x - x) < 1.5 && Math.abs(last.y - y) < 1.5 &&
      Math.abs(last.w - w) < 1.5 && Math.abs(last.h - h) < 1.5;
    state.last = next;
    if (same && !force) return;
    // Keep the script's screen coordinates stable while the host moves the WebView. The root
    // element must carry the transform: fixed nodes appended to documentElement ignore body.
    state.offset.x = x;
    state.offset.y = y;
    document.documentElement.style.transform = 'translate(' + (-x) + 'px,' + (-y) + 'px)';
    if (host) host.rect(x, y, w, h);
  };

  let scheduled = false;
  const schedule = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; measure(false); });
  };
  new MutationObserver(schedule).observe(document.body, {
    subtree: true, childList: true, attributes: true,
  });
  let lastTick = 0;
  const tick = timestamp => {
    if (!document.hidden && timestamp - lastTick > 120) {
      lastTick = timestamp;
      measure(false);
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);

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
    schedule();
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
    schedule();
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
    try { state.frame?.contentWindow?.dispatchEvent?.(new Event('pagehide')); }
    catch (error) { console.error(error); }
  };

  measure(true);
})();

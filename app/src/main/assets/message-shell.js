/* Xujing message shell.
 * Wraps a scripted card in a same-origin iframe and exposes the handful of host-page
 * interfaces preset HTML/JS usually touches: window.parent.document / #send_textarea /
 * triggerSlash / .mes_text / frameElement height. The card runtime itself keeps running
 * inside the frame; this file only bridges replies, session events and the snapshot.
 */
(() => {
  'use strict';
  const initialContext = window.__XUJING_CONTEXT__ || { runtimeToken: '', theme: {} };
  const messageHtml = typeof window.__XUJING_MESSAGE_HTML__ === 'string' ? window.__XUJING_MESSAGE_HTML__ : '';
  let context = initialContext;
  let theme = Object.assign({}, initialContext.theme || {}, window.__XUJING_THEME__ || {});
  const frame = document.getElementById('xujing-message-frame');
  const textarea = document.getElementById('send_textarea');
  const shell = document.querySelector('.mes_text');
  const bridge = () => window.XujingBridge;
  const frameWindow = () => (frame ? frame.contentWindow : null);

  /* ---------------- theme ---------------- */
  const applyTheme = next => {
    if (next && typeof next === 'object') theme = Object.assign({}, theme, next);
    const nodes = [shell, document.body];
    for (const node of nodes) {
      if (!node || !node.style) continue;
      if (theme.textColor) node.style.color = String(theme.textColor);
      if (theme.backgroundColor) node.style.backgroundColor = String(theme.backgroundColor);
      if (theme.fontSize) node.style.fontSize = typeof theme.fontSize === 'number' ? theme.fontSize + 'px' : String(theme.fontSize);
      if (theme.fontFamily) node.style.fontFamily = String(theme.fontFamily);
      if (typeof theme.lineHeight === 'number') node.style.lineHeight = String(theme.lineHeight);
    }
  };
  applyTheme(theme);

  /* ---------------- host requests ---------------- */
  // Replies from the Kotlin bridge arrive through window.__xujingReply. Requests the frame
  // issued are forwarded; requests the shell itself issued resolve here.
  const pending = new Map();
  let sequence = 0;
  const request = (method, payload = {}) => new Promise((resolve, reject) => {
    const id = 'shell:' + (++sequence);
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('卡片宿主请求超时：' + method)); }, 180000);
    pending.set(id, { resolve, reject, timer });
    try {
      if (!bridge() || typeof bridge().request !== 'function') throw new Error('卡片宿主尚未就绪');
      bridge().request(context.runtimeToken || '', id, method, JSON.stringify(payload));
    } catch (error) {
      pending.delete(id); clearTimeout(timer); reject(error);
    }
  });

  /* ---------------- frame forwarding ---------------- */
  const queue = [];
  let flushTimer = 0;
  const scheduleFlush = () => { if (!flushTimer) flushTimer = setTimeout(flush, 80); };
  function flush() {
    if (flushTimer) { clearTimeout(flushTimer); flushTimer = 0; }
    if (!queue.length) return;
    const view = frameWindow();
    if (!view) return;
    const remaining = [];
    for (const item of queue) {
      const fn = view[item.name];
      if (typeof fn !== 'function') { remaining.push(item); continue; }
      try { fn.apply(view, item.args); } catch (error) { console.error(error); }
    }
    queue.splice(0, queue.length, ...remaining);
    if (queue.length) scheduleFlush();
  }
  const dispatch = (name, ...args) => {
    const view = frameWindow();
    if (view && typeof view[name] === 'function') {
      try { return view[name](...args); } catch (error) { console.error(error); return undefined; }
    }
    queue.push({ name, args });
    scheduleFlush();
    return undefined;
  };

  window.__xujingReply = (id, value) => {
    const key = String(id);
    const task = pending.get(key);
    if (task) {
      pending.delete(key); clearTimeout(task.timer);
      if (value && value.error) task.reject(new Error(value.error)); else task.resolve(value);
      return;
    }
    dispatch('__xujingReply', key, value);
  };
  window.__xujingUpdate = next => {
    if (next && typeof next === 'object') { context = next; applyTheme(next.theme); scheduleMeasure(); }
    dispatch('__xujingUpdate', next);
  };
  window.__xujingSessionEvent = (id, name, payload) => dispatch('__xujingSessionEvent', id, name, payload);

  /* ---------------- input + slash commands ---------------- */
  const setInput = value => {
    if (!textarea) return false;
    textarea.value = value == null ? '' : String(value);
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  };
  window.__xujingSetInput = setInput;
  window.triggerSlash = async command => {
    const source = String(command == null ? '' : command);
    const match = /^\s*\/([A-Za-z]+)(?:\s+([\s\S]*))?$/.exec(source);
    const name = (match && match[1] ? match[1] : '').toLowerCase();
    const rest = match && match[2] != null ? match[2] : '';
    if (name === 'setinput') return setInput(rest);
    if (name === 'send') { await request('send', { text: rest }); return true; }
    throw new Error('暂不支持的斜杠命令：' + source);
  };
  if (textarea) textarea.addEventListener('input', () => {
    request('setInput', { text: textarea.value }).catch(error => console.error(error));
  });

  /* ---------------- height ---------------- */
  const MAX_HEIGHT = 12000;
  let reported = 0, explicit = 0, measureTimer = 0;

  const measureContent = () => {
    const view = frameWindow();
    if (!view) return 0;
    try {
      const doc = view.document, body = doc && doc.body;
      if (!body) return 0;
      const box = body.getBoundingClientRect();
      const style = view.getComputedStyle(body);
      let bottom = box.top + (parseFloat(style.paddingTop) || 0);
      for (const child of body.children) {
        const rect = child.getBoundingClientRect();
        if (rect.width <= 0 && rect.height <= 0) continue;
        // A fixed-height scroll container (100vh / fixed / overflow:auto) only exposes its
        // viewport through getBoundingClientRect. Use scrollHeight when it overflows so the
        // message grows instead of hiding the rest behind an inner scrollbar.
        const scroll = child.scrollHeight || 0;
        const inner = scroll > (child.clientHeight || 0) + 1 ? Math.max(rect.height, scroll) : rect.height;
        bottom = Math.max(bottom, rect.top + inner);
      }
      // Same measurement as card-runtime's resize(), so the two never fight over the height.
      return Math.ceil(bottom - box.top + (parseFloat(style.paddingBottom) || 0));
    } catch (error) { return 0; }
  };

  const applyHeight = value => {
    const next = Math.max(24, Math.min(MAX_HEIGHT, Math.round(Number(value) || 0)));
    if (!next) return;
    reported = next;
    const current = frame ? (parseFloat(frame.style.height) || 0) : 0;
    if (frame && frame.style && Math.abs(current - next) > 1) frame.style.height = next + 'px';
    // Re-assert on every measurement: the card runtime may have reported a lower height after
    // the card set an explicit frameElement height, and the host keeps the last value.
    try { if (bridge() && typeof bridge().height === 'function') bridge().height(next); } catch (error) { console.error(error); }
  };

  const measure = () => {
    measureTimer = 0;
    const content = measureContent();
    if (content <= 0 && explicit <= 0) return;
    applyHeight(Math.max(content, explicit));
  };
  const scheduleMeasure = () => { if (!measureTimer) measureTimer = setTimeout(measure, 60); };

  // A card may size itself through window.frameElement.style.height / .height.
  const heightValue = raw => {
    const text = String(raw == null ? '' : raw).trim();
    if (!/^[\d.]+(px)?$/i.test(text)) return 0;
    const value = parseFloat(text);
    return Number.isFinite(value) && value > 0 ? value : 0;
  };
  const syncExplicit = () => {
    if (!frame) return;
    const styled = heightValue(frame.style.height);
    const attributed = heightValue(frame.getAttribute('height'));
    const value = styled || attributed;
    if (!value) return;
    const rounded = Math.round(value);
    if (Math.abs(rounded - reported) <= 2) return; // written by this shell
    explicit = Math.max(24, Math.min(MAX_HEIGHT, rounded));
    scheduleMeasure();
  };

  /* ---------------- frame lifecycle ---------------- */
  const observed = new WeakSet();
  const observeContent = () => {
    const view = frameWindow();
    if (!view) return;
    try {
      const doc = view.document;
      if (!doc || !doc.documentElement || observed.has(doc.documentElement)) return;
      observed.add(doc.documentElement);
      if (typeof view.ResizeObserver === 'function' && doc.body) new view.ResizeObserver(scheduleMeasure).observe(doc.body);
      if (typeof view.MutationObserver === 'function') new view.MutationObserver(scheduleMeasure).observe(doc.documentElement, { childList: true, subtree: true, characterData: true });
    } catch (error) { console.error(error); }
  };

  if (frame) {
    try { frame.setAttribute('scrolling', 'no'); } catch (error) { console.error(error); }
    try { new MutationObserver(syncExplicit).observe(frame, { attributes: true, attributeFilter: ['style', 'height'] }); }
    catch (error) { console.error(error); }
    frame.addEventListener('load', () => {
      observeContent();
      flush();
      scheduleMeasure();
      setTimeout(() => { flush(); scheduleMeasure(); }, 120);
      setTimeout(scheduleMeasure, 480);
    });
    // srcdoc keeps the frame same-origin with the shell and lets the injected base URL
    // resolve /runtime/* through shouldInterceptRequest. Older WebViews get document.write.
    if ('srcdoc' in frame) frame.srcdoc = messageHtml;
    else {
      try {
        const doc = frame.contentDocument;
        if (doc) { doc.open(); doc.write(messageHtml); doc.close(); }
      } catch (error) { console.error(error); }
    }
  }
  window.addEventListener('resize', scheduleMeasure);
  applyTheme(theme);
  scheduleMeasure();
})();

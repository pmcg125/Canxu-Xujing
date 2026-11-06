(() => {
  let started = false;
  let context = {};
  const scripts = new Map();
  const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const safe = value => JSON.stringify(value).replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  const scriptTag = source => '<scr' + 'ipt src="' + source + '"></scr' + 'ipt>';
  const inlineScript = source => '<scr' + 'ipt>' + source + '</scr' + 'ipt>';
  const pageFor = (script, snapshot) => {
    const runtimeContext = { ...snapshot, runtimeToken: script.id };
    const metadata = { id: script.id, name: script.name, info: script.info, buttons: script.buttons || [] };
    return '<!doctype html><html><head><meta charset="utf-8"><base href="https://cards.xujing.invalid/">' +
      inlineScript(`window.__XUJING_CONTEXT__=${safe(runtimeContext)};window.__XUJING_SCRIPT__=${safe(metadata)};`) +
      scriptTag('/runtime/zod.js') + scriptTag('/runtime/ejs.js') +
      scriptTag('/runtime/card-runtime.js') + scriptTag('/runtime/mvu.js') +
      scriptTag('/runtime/template.js') + scriptTag('/runtime/session-script-runtime.js') +
      '</head><body></body></html>';
  };
  const notifyInitialized = (script, error = '') => {
    try { XujingBridge.initialized(script.id, String(error || '')); } catch (bridgeError) { console.error(bridgeError); }
  };
  const frameRecord = script => {
    const frame = document.createElement('iframe');
    frame.setAttribute('aria-hidden', 'true');
    frame.tabIndex = -1;
    frame.style.cssText = 'position:absolute;width:1px;height:1px;left:-10000px;top:-10000px;border:0;visibility:hidden';
    const record = { ...script, frame, initialized: false };
    scripts.set(script.id, record);
    frame.onload = async () => {
      try {
        const child = frame.contentWindow;
        if (typeof child?.__xujingLoadSessionScript !== 'function') throw new Error('脚本运行时未加载');
        child.__xujingUpdate?.({ ...context, runtimeToken: script.id });
        await child.__xujingLoadSessionScript(String(script.content || ''));
      } catch (error) { notifyInitialized(script, error?.message || error); }
    };
    frame.srcdoc = pageFor(script, context);
    document.body.appendChild(frame);
  };

  window.__xujingUpdate = next => {
    context = clone(next || {});
    for (const script of scripts.values()) {
      try { script.frame.contentWindow?.__xujingUpdate?.({ ...context, runtimeToken: script.id }); }
      catch (error) { console.error(error); }
    }
  };
  window.__xujingReplyForFrame = (runtimeToken, requestId, result) => {
    const child = scripts.get(String(runtimeToken))?.frame.contentWindow;
    child?.__xujingReply?.(requestId, result);
  };
  window.__xujingRunButton = async (scriptId, name) => {
    const child = scripts.get(String(scriptId))?.frame.contentWindow;
    if (typeof child?.__xujingRunButton !== 'function') return false;
    return await child.__xujingRunButton(String(name));
  };
  window.__xujingDispatchSessionEvent = async (requestId, event, payload, excludeToken = '') => {
    const multiple = Array.isArray(payload);
    const args = multiple ? payload : [payload];
    for (const script of scripts.values()) {
      if (script.id === excludeToken) continue;
      const child = script.frame.contentWindow;
      if (typeof child?.__xujingSessionEvent !== 'function') continue;
      try { await child.__xujingSessionEvent(event, args); }
      catch (error) { console.error(`角色脚本事件 ${event} 执行失败`, error); }
    }
    XujingBridge.complete(String(requestId), JSON.stringify(multiple ? args : (args[0] ?? null)), '');
  };
  window.__xujingLoadSessionScripts = (snapshot, sources) => {
    if (started) return;
    started = true;
    context = clone(snapshot || {});
    for (const value of sources || []) {
      const script = {
        ...value,
        id: String(value?.id || ''),
        name: String(value?.name || '角色脚本'),
        info: String(value?.info || ''),
        content: String(value?.content || ''),
        buttons: clone(value?.buttons || []),
      };
      if (!script.id) continue;
      frameRecord(script);
    }
  };
  // Best-effort bridge for presets that build a floating panel through parent.document.
  // Only nodes appended to <body> with inline position:fixed are forwarded; iframes are ignored.
  const installPanelCompat = () => {
    if (window.__xujingPanelCompat) return;
    window.__xujingPanelCompat = true;
    const MAX = 400 * 1024;
    let current = null, seq = 0, timer = 0;
    const px = value => { const match = /(\d+(?:\.\d+)?)px/.exec(String(value || '')); return match ? Math.round(Number(match[1])) : 0; };
    const styles = () => Array.from(document.querySelectorAll('style')).map(node => node.textContent || '').join('\n');
    const request = (method, payload) => {
      try { XujingBridge.request('xujing-session-host', 'panel-' + (++seq), method, JSON.stringify(payload || {})); }
      catch (error) { console.error(error); }
    };
    const publish = node => {
      if (!node || !node.isConnected) return;
      const html = styles() + node.outerHTML;
      if (!html || html.length > MAX) return;
      request('setScriptPanel', {
        html,
        title: String(node.getAttribute('data-title') || node.title || '脚本面板'),
        width: px(node.style.width) || 320,
        height: px(node.style.height) || 260,
        position: 'bottom-end',
      });
    };
    const schedule = records => {
      if (!current) return;
      const touches = !records || records.some(record =>
        current.contains(record.target) || record.target === current || document.head.contains(record.target));
      if (!touches) return;
      clearTimeout(timer);
      timer = setTimeout(() => publish(current), 180);
    };
    const looksFloating = node => {
      if (!node || node.nodeType !== 1) return false;
      if (['IFRAME', 'SCRIPT', 'STYLE', 'LINK', 'META', 'TEMPLATE', 'NOSCRIPT'].includes(node.tagName)) return false;
      return String(node.getAttribute('style') || '').replace(/\s+/g, '').toLowerCase().includes('position:fixed');
    };
    const appendChild = Node.prototype.appendChild;
    Node.prototype.appendChild = function (node) {
      const result = appendChild.call(this, node);
      try { if (this === document.body && looksFloating(node)) { current = node; publish(node); } }
      catch (error) { console.error(error); }
      return result;
    };
    new MutationObserver(schedule).observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true });
    new MutationObserver(schedule).observe(document.head, { subtree: true, childList: true, characterData: true });
    setInterval(() => {
      if (!current) return;
      if (!current.isConnected || !document.body.contains(current)) {
        current = null;
        request('clearScriptPanel', {});
      }
    }, 1000);
  };
  if (document.body) installPanelCompat();
  else document.addEventListener('DOMContentLoaded', installPanelCompat, { once: true });

  window.addEventListener('pagehide', () => {
    for (const script of scripts.values()) script.frame.remove();
    scripts.clear();
  }, { once: true });
})();

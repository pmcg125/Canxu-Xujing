(() => {
  const script = window.__XUJING_SCRIPT__ || {};
  const scriptId = String(script.id || '');
  const localEmit = window.eventSource.emit.bind(window.eventSource);
  let scriptInfo = String(script.info || '');
  let buttons = Array.isArray(script.buttons) ? JSON.parse(JSON.stringify(script.buttons)) : [];
  const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const stringHash = (value, seed = 0) => {
    value = typeof value === 'string' ? value : '';
    let h1 = 0xdeadbeef ^ seed;
    let h2 = 0x41c6ce57 ^ seed;
    for (let index = 0; index < value.length; index += 1) {
      const code = value.charCodeAt(index);
      h1 = Math.imul(h1 ^ code, 2654435761);
      h2 = Math.imul(h2 ^ code, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
  };
  const buttonEvent = name => scriptId + '_' + stringHash(String(name));
  const publishButtons = () => {
    const visible = buttons.map(button => {
      const name = String(button?.name ?? '');
      return name ? {
        scriptId,
        scriptName: String(script.name || '角色脚本'),
        name,
        label: String(button.label ?? button.title ?? button.text ?? name),
        visible: button.visible !== false,
        enabled: button.enabled !== false,
      } : null;
    }).filter(Boolean);
    XujingBridge.buttons(scriptId, JSON.stringify(visible));
  };

  window.getScriptId = () => scriptId;
  window.getScriptName = () => String(script.name || '角色脚本');
  window.getScriptInfo = () => scriptInfo;
  window.replaceScriptInfo = value => { scriptInfo = String(value ?? ''); };
  window.getButtonEvent = name => buttonEvent(name);
  window.getScriptButtons = () => clone(buttons);
  window.replaceScriptButtons = (first, second) => {
    buttons = clone(Array.isArray(first) ? first : (second || []));
    publishButtons();
    return clone(buttons);
  };
  window.updateScriptButtonsWith = async updater => {
    const next = await updater(window.getScriptButtons());
    return window.replaceScriptButtons(next);
  };
  window.appendInexistentScriptButtons = (first, second) => {
    const incoming = Array.isArray(first) ? first : (second || []);
    const next = incoming.filter(button => !buttons.some(old => old?.name === button?.name));
    buttons = [...buttons, ...clone(next)];
    publishButtons();
    return clone(buttons);
  };
  window.eventOnButton = (name, listener) => {
    const event = buttonEvent(name);
    if (!scriptId || typeof listener !== 'function') return { stop: () => false };
    const stop = window.eventSource.on(event, listener);
    return { stop: typeof stop === 'function' ? stop : () => window.eventSource.removeListener(event, listener) };
  };

  window.__xujingHostEventDepth = 0;
  window.eventEmit = async (name, ...args) => {
    const local = await localEmit(name, ...args);
    if (window.__xujingHostEventDepth > 0) return local;
    try {
      return await window.__xujingRequest('broadcastRuntimeEvent', {
        event: name,
        args,
        sourceKind: 'session',
        sourceToken: scriptId,
      });
    } catch (error) { console.error(error); return local; }
  };
  window.eventEmitAndWait = window.eventEmit;
  window.eventSource.emit = window.eventEmit;
  window.eventSource.emitAndWait = window.eventEmit;
  const helperGetContext = window.TavernHelper?.getContext;
  if (typeof helperGetContext === 'function') {
    const getContext = (...args) => {
      const value = helperGetContext(...args);
      if (value?.eventSource) {
        value.eventSource.emit = window.eventEmit;
        value.eventSource.emitAndWait = window.eventEmit;
      }
      return value;
    };
    window.TavernHelper.getContext = getContext;
    if (window.SillyTavern) window.SillyTavern.getContext = getContext;
  }
  window.iframe_events = Object.freeze({
    MESSAGE_IFRAME_RENDER_STARTED: 'message_iframe_render_started',
    MESSAGE_IFRAME_RENDER_ENDED: 'message_iframe_render_ended',
    GENERATION_STARTED: 'js_generation_started',
    STREAM_TOKEN_RECEIVED_FULLY: 'js_stream_token_received_fully',
    STREAM_TOKEN_RECEIVED_INCREMENTALLY: 'js_stream_token_received_incrementally',
    GENERATION_ENDED: 'js_generation_ended',
  });
  window.__xujingSessionEvent = async (event, payload) => {
    const multiple = Array.isArray(payload);
    const args = multiple ? payload : [payload];
    // JSON transport turns accessors into ordinary properties. Rebuild the alias for every
    // receiver; when both fields are present, messages is the canonical value.
    if (event === 'generate_after_data' && args[0] && typeof args[0] === 'object') {
      const data = args[0], has = key => Object.prototype.hasOwnProperty.call(data, key);
      if (has('messages') || has('prompt')) {
        if (!has('messages')) data.messages = data.prompt;
        Object.defineProperty(data, 'prompt', {
          enumerable: true, configurable: true,
          get: () => data.messages,
          set: value => { data.messages = value; },
        });
      }
    }
    window.__xujingHostEventDepth += 1;
    try {
      await localEmit(event, ...args);
      return multiple ? args : (args[0] ?? null);
    } finally { window.__xujingHostEventDepth = Math.max(0, window.__xujingHostEventDepth - 1); }
  };
  window.__xujingRunButton = async name => {
    if (!buttons.some(button => String(button?.name ?? '') === String(name))) return false;
    await localEmit(buttonEvent(name));
    return true;
  };
  const updateContext = window.__xujingUpdate;
  window.__xujingUpdate = next => updateContext?.({ ...next, runtimeToken: scriptId });
  window.__xujingLoadSessionScript = async content => {
    try {
      let source = String(content || '');
      source = source.replace(/(['"])(\/(?:scripts\/[^'"\s]+|script\.js))\1/g,
        (_, quote, path) => quote + 'https://cards.xujing.invalid' + path + quote);
      const url = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
      try { await import(url); } finally { URL.revokeObjectURL(url); }
      await localEmit('chat_id_changed');
      publishButtons();
      XujingBridge.initialized(scriptId, '');
      return true;
    } catch (error) {
      XujingBridge.initialized(scriptId, String(error?.stack || error?.message || error));
      return false;
    }
  };
})();

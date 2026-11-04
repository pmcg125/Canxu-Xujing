/* Xujing card host compatibility subset. No credentials, filesystem or Android hardware bridge. */
(() => {
  'use strict';
  let context = window.__XUJING_CONTEXT__ || { variables: {}, chat: [] };
  const pending = new Map(), events = new Map();
  let sequence = 0, stateSynchronized = false, initRevision = 0;
  const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const request = (method, payload = {}) => new Promise((resolve, reject) => {
    const id = String(++sequence);
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('卡片宿主请求超时：' + method)); }, 180000);
    pending.set(id, { resolve, reject, timer });
    try { XujingBridge.request(context.runtimeToken || '', id, method, JSON.stringify(payload)); }
    catch (error) { pending.delete(id); clearTimeout(timer); reject(error); }
  });
  window.__xujingReply = (id, value) => {
    const task = pending.get(String(id)); if (!task) return;
    pending.delete(String(id)); clearTimeout(task.timer);
    if (value && value.error) task.reject(new Error(value.error)); else task.resolve(value);
  };
  const eventName = name => ({ VARIABLE_INITIALIZED:'mag_variable_initialized', VARIABLE_UPDATE_ENDED:'mag_variable_update_ended', MESSAGE_UPDATED:'message_updated' }[name] || name);
  const eventOn = (name, handler) => { name=eventName(name); if (!events.has(name)) events.set(name, new Set()); events.get(name).add(handler); const stop=()=>eventOff(name,handler); stop.stop=stop; return stop; };
  const eventOff = (name, handler) => events.get(eventName(name))?.delete(handler);
  const eventEmit = async (name, ...args) => { for (const handler of [...(events.get(eventName(name)) || [])]) await handler(...args); return args; };
  const eventOnce = (name, handler) => { const wrapped = (...args) => { eventOff(name, wrapped); return handler(...args); }; return eventOn(name, wrapped); };
  window.eventOn=eventOn;
  window.eventOff=eventOff;
  window.eventOnce=eventOnce;
  function pathParts(path) { return String(path).replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean); }
  function getPath(object, path, fallback) { let value = object; for (const key of pathParts(path)) { if (value == null) return fallback; value = value[key]; } return value === undefined ? fallback : value; }
  function setPath(object, path, value) { const keys = pathParts(path); let target = object; keys.forEach((key, index) => { if (['__proto__', 'constructor', 'prototype'].includes(key)) throw new Error('非法变量路径'); if (index === keys.length - 1) target[key] = value; else target = target[key] ||= {}; }); return object; }
  const scopedMessage = option => {
    const chat=context.chat || [], id=option.message_id ?? 'latest';
    if (id==='latest') return [...chat].reverse().find(m=>m.role!=='system');
    const index=Number(id); return Number.isInteger(index) ? chat[index<0 ? chat.length+index : index] : undefined;
  };
  const scope = (option={}) => {
    const type=option.type || 'chat';
    if(type==='chat') return {type};
    if(type!=='message') throw Error('尚未实现的变量作用域：'+type);
    const message=scopedMessage(option); if(!message) throw Error('消息不存在');
    return {type,messageId:message.id,swipe_id:message.swipe_id};
  };
  const getVariables = (option={}) => { const selected=scope(option); return clone(selected.type==='chat' ? context.variables || {} : context.chat.find(m=>m.id===selected.messageId)?.variables || {}); };
  let variableWrites=0;
  const replaceVariables = async (variables, option={}) => {
    const selected=scope(option), expected=getVariables(option), next=clone(variables);
    variableWrites++;
    try {
      const result=await request('replaceVariables',{variables:next,option:selected,expected});
      if(selected.type==='chat') context.variables=clone(result.value);
      else { const message=context.chat.find(m=>m.id===selected.messageId); if(message) message.variables=clone(result.value); }
      await eventEmit('mag_variable_update_ended',clone(result.value)).catch(console.error);
      return clone(result.value);
    } finally { variableWrites--; }
  };
  const mergeReplacingArrays = (left,right) => {
    if (Array.isArray(right)) return clone(right);
    if (!right || typeof right !== 'object') return clone(right);
    const result = left && typeof left === 'object' && !Array.isArray(left) ? clone(left) : {};
    for (const [key,value] of Object.entries(right)) result[key] = mergeReplacingArrays(result[key],value);
    return result;
  };
  const insertOrAssignVariables = (variables,option) => replaceVariables(mergeReplacingArrays(getVariables(option),variables),option);
  const updateVariablesWith = async (updater,option) => { const variables = getVariables(option); const updated = await updater(variables); return replaceVariables(updated === undefined ? variables : updated,option); };
  const getChatMessages = async (range = '0-{{lastMessageId}}') => {
    const chat = context.chat || [];
    if (typeof range === 'number' || /^-?\d+$/.test(String(range))) { let index = Number(range); if (index < 0) index += chat.length; return chat[index] ? [clone(chat[index])] : []; }
    const match = /^(\d+)-(\d+|\{\{lastMessageId\}\})$/.exec(String(range));
    return clone(match ? chat.slice(Number(match[1]), match[2].includes('{') ? undefined : Number(match[2]) + 1) : chat);
  };
  const setChatMessages = async messages => { const result = await request('setChatMessages', messages); await eventEmit('MESSAGE_UPDATED'); return result; };
  const getWorldbookNames = () => (context.worldbookNames || []).slice();
  const worldbookMutation = async (method, payload) => {
    const result = await request(method, payload);
    if (Array.isArray(result.names)) context.worldbookNames = result.names.slice();
    if (result.value !== false) await eventEmit(method === 'rebindCharWorldbooks' ? 'worldinfo_settings_updated' : 'worldinfo_updated', payload.name, payload.data);
    return result.value;
  };
  const loadWorldInfo = async name => (await request('loadWorldInfo', { name })).value;
  const getWorldbook = name => request('getWorldbook', { name });
  const saveWorldInfo = (name, data) => worldbookMutation('saveWorldInfo', { name, data });
  const createWorldbook = (name, entries = []) => worldbookMutation('createWorldbook', { name, entries });
  const deleteWorldbook = name => worldbookMutation('deleteWorldbook', { name });
  const rebindCharWorldbooks = (character, options) => worldbookMutation('rebindCharWorldbooks', { character, options, characterId: context.characterId });
  const presetCache = new Map();
  const getPresetNames = () => (context.presets || []).map(preset => preset.name);
  const selectedPreset = () => (context.presets || []).find(preset => preset.id === context.activePresetId);
  const loadPreset = async name => {
    const selected = !name || name === 'in_use' ? context.activePresetId : name;
    if (!selected) throw new Error('当前会话未选择预设');
    const record = await request('loadPreset', { name: selected });
    presetCache.set(record.id, clone(record.data)); presetCache.set(record.name, clone(record.data));
    return clone(record.data);
  };
  const getPreset = name => {
    const selected = !name || name === 'in_use' ? context.activePresetId : name;
    if (!selected || !presetCache.has(selected)) throw new Error('预设尚未加载，请先调用 loadPreset');
    return clone(presetCache.get(selected));
  };
  const selectPreset = async id => {
    const result = await request('selectPreset', { id: id == null ? '' : String(id) });
    if (result.value !== false) { context.activePresetId = result.activePresetId; await eventEmit('preset_changed', context.activePresetId); }
    return result.value;
  };
  const presetManager = {
    apiId: 'openai', getAllPresets: getPresetNames,
    getPresetNames, getActivePresetId: () => context.activePresetId ?? null,
    getSelectedPresetName: () => selectedPreset()?.name ?? '',
    getSelectedPreset: () => selectedPreset()?.name ?? '',
    getPresetSettings: getPreset, getPreset,
    getCompletionPresetByName: name => { try { return getPreset(name); } catch (_) { return undefined; } },
    findPreset: name => getPresetNames().includes(name) ? name : undefined,
    loadPreset, selectPreset, setActivePresetId: selectPreset,
    getPresetList: () => { const names=getPresetNames(); const loaded=names.flatMap(name => presetCache.has(name) ? [{name,data:clone(presetCache.get(name))}] : []); return { presets:loaded.map(item=>item.data), preset_names:Object.fromEntries(loaded.map((item,index)=>[item.name,index])) }; },
  };
  const getPresetManager = () => presetManager;
  const getContext = () => ({ chat: context.chat || [], name1: context.name1, name2: context.name2, chatId: context.sessionId, chatMetadata: { variables: getVariables() }, eventSource: { on: eventOn, removeListener: eventOff, once: eventOnce, emit: eventEmit }, saveChat: async () => true, saveMetadata: async () => replaceVariables(context.variables), getPresetManager,
    getPreset, getPresetNames, loadPreset,
    substituteParams: text => api.substituteParams(text),
    sendOpenAIRequest: async (_type, messages, signal) => {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      const content = await request('generate', { ordered_prompts: messages });
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      return { choices: [{ message: { role: 'assistant', content } }] };
    },
  });
  const api = { getVariables, replaceVariables, insertOrAssignVariables, updateVariablesWith, getChatMessages, setChatMessages, getContext, eventOn, eventOff, eventOnce, eventEmit,
    getWorldbookNames, loadWorldInfo, getWorldbook, saveWorldInfo, createWorldbook, deleteWorldbook, rebindCharWorldbooks,
    getPresetManager, getPresetNames, getAllPresets: getPresetNames, loadPreset, getPreset, getLoadedPresetName: () => selectedPreset()?.name ?? '', getActivePresetId: () => context.activePresetId ?? null, setActivePresetId: selectPreset,
    getCurrentMessageId: () => Math.max(0, (context.chat || []).findIndex(m => m.id === context.messageId)),
    getLastMessageId: () => (context.chat || []).length - 1,
    generate: options => request('generate', typeof options === 'string' ? { user_input: options } : options),
    generateRaw: options => request('generate', options),
    triggerSlash: async command => { if (String(command).startsWith('/send ')) return request('send', { text: String(command).slice(6) }); throw new Error('暂不支持的斜杠命令'); },
    substituteParams: text => String(text).replace(/\{\{user\}\}/gi, context.name1 || '我').replace(/\{\{char\}\}/gi, context.name2 || '').replace(/\{\{getvar::([^}]+)\}\}/g, (_, key) => String(getPath(context.variables, key, ''))),
  };
  window.TavernHelper = api;
  window.__xujingRequest = request;
  Object.entries(api).forEach(([name, value]) => { if (!(name in window)) window[name] = value; });
  window.SillyTavern = { getContext, getPresetManager };
  window.eventSource = { on:eventOn, once:eventOnce, removeListener:eventOff, emit:eventEmit, emitAndWait:eventEmit,
    removeAllListeners:name=>name===undefined ? events.clear() : events.delete(eventName(name)),
    makeLast:eventOn, makeFirst:(name,handler)=>{ name=eventName(name); events.set(name,new Set([handler,...(events.get(name)||[])])); return eventOn(name,handler); }
  };
  window.__xujingHostEventDepth=0;
  window.eventEmit=async(name,...args)=>{
    const local=await eventEmit(name,...args);
    if(window.__xujingHostEventDepth>0) return local;
    try { return await request('broadcastRuntimeEvent',{event:name,args,sourceKind:'card',sourceToken:context.runtimeToken}); }
    catch(error) { console.error(error); return local; }
  };
  window.eventEmitAndWait=window.eventEmit;
  window.__xujingSessionEvent=async(id,name,payload)=>{
    const multiple=Array.isArray(payload),args=multiple?payload:[payload];
    // PiuPiu passes generate_after_data as { prompt, dryRun }; keep prompt as a live alias of messages.
    if(name==='generate_after_data'&&args[0]&&typeof args[0]==='object'&&args[0].messages&&!Object.prototype.hasOwnProperty.call(args[0],'prompt'))
      Object.defineProperty(args[0],'prompt',{enumerable:true,configurable:true,get:()=>args[0].messages,set:value=>{args[0].messages=value;}});
    window.__xujingHostEventDepth++;
    try {
      await eventEmit(name,...args);
      XujingBridge.eventComplete(id,JSON.stringify(multiple?args:args[0]??null),'');
    } catch(error) {
      XujingBridge.eventComplete(id,'null',String(error?.message||error));
    } finally { window.__xujingHostEventDepth=Math.max(0,window.__xujingHostEventDepth-1); }
  };
  window.tavern_events={MESSAGE_UPDATED:'message_updated', MESSAGE_RECEIVED:'message_received', MESSAGE_SENT:'message_sent', CHAT_CHANGED:'chat_id_changed', GENERATION_STARTED:'generation_started', GENERATION_ENDED:'generation_ended', GENERATION_STOPPED:'generation_stopped', CHAT_COMPLETION_PROMPT_READY:'chat_completion_prompt_ready', GENERATE_AFTER_DATA:'generate_after_data'};
  window.iframe_events=Object.freeze({
    MESSAGE_IFRAME_RENDER_STARTED:'message_iframe_render_started',
    MESSAGE_IFRAME_RENDER_ENDED:'message_iframe_render_ended',
    GENERATION_STARTED:'js_generation_started',
    STREAM_TOKEN_RECEIVED_FULLY:'js_stream_token_received_fully',
    STREAM_TOKEN_RECEIVED_INCREMENTALLY:'js_stream_token_received_incrementally',
    GENERATION_ENDED:'js_generation_ended'
  });
  window.initializeGlobal=async (name,value)=>{ window[name]=value; await eventEmit('global_'+name+'_initialized'); };
  window.waitGlobalInitialized=async name=>{
    if(!(name in window)) await new Promise(resolve=>eventOnce('global_'+name+'_initialized',resolve));
    if(name==='Mvu') while(!stateSynchronized) await new Promise(resolve=>setTimeout(resolve,20));
    return window[name];
  };
  window.__xujingUpdate = next => {
    const oldVariables = JSON.stringify(context.variables || {});
    const previousRevision = initRevision;
    for (const prior of context.presets || []) {
      const current = (next.presets || []).find(item => item.id === prior.id);
      if (!current || current.name !== prior.name || current.revision !== prior.revision) {
        presetCache.delete(prior.id); presetCache.delete(prior.name);
      }
    }
    context = next;
    const revision = Number(next.initRevision || 0);
    if (revision !== previousRevision) {
      initRevision = revision;
      if (revision > 0) eventEmit('mag_variable_initialized', clone(next.variables || {})).catch(console.error);
    }
    if (oldVariables !== JSON.stringify(next.variables || {}) && !variableWrites) eventEmit('mag_variable_update_ended', clone(next.variables || {})).catch(console.error);
    stateSynchronized = true;
  };
  // scrollHeight/offsetHeight are clamped to the WebView viewport, so a short card can never shrink.
  // Measure the content box of the body's children instead.
  const resize = () => {
    const body = document.body;
    if (!body) return;
    const box = body.getBoundingClientRect();
    const style = getComputedStyle(body);
    let bottom = box.top + (parseFloat(style.paddingTop) || 0);
    for (const child of body.children) {
      const rect = child.getBoundingClientRect();
      if (rect.width > 0 || rect.height > 0) bottom = Math.max(bottom, rect.bottom);
    }
    let height = Math.ceil(bottom - box.top + (parseFloat(style.paddingBottom) || 0));
    if (height < 24) height = 24;
    XujingBridge.height(height);
  };
  window.__xujingCardReady=async()=>{
    resize();
    try { await window.eventEmit('message_iframe_render_ended',api.getCurrentMessageId()); }
    finally { if(typeof XujingBridge.ready==='function') XujingBridge.ready(); }
  };
  document.addEventListener('DOMContentLoaded', () => { new ResizeObserver(resize).observe(document.body); resize(); });
  window.addEventListener?.('pagehide',()=>{ for(const task of pending.values()){clearTimeout(task.timer);task.reject(Error('角色卡已关闭'));} pending.clear();events.clear(); });
})();

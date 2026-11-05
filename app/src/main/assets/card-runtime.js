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
    // PiuPiu keeps one global preset bucket (no per-preset-name split).
    if(type==='preset') return {type:'preset'};
    if(type!=='message') throw Error('尚未实现的变量作用域：'+type);
    const message=scopedMessage(option); if(!message) throw Error('消息不存在');
    return {type,messageId:message.id,swipe_id:message.swipe_id};
  };
  const getVariables = (option={}) => { const selected=scope(option); if(selected.type==='preset') return clone(context.presetVariables || {}); return clone(selected.type==='chat' ? context.variables || {} : context.chat.find(m=>m.id===selected.messageId)?.variables || {}); };
  let variableWrites=0;
  const replaceVariables = async (variables, option={}) => {
    const selected=scope(option), expected=getVariables(option), next=clone(variables);
    variableWrites++;
    try {
      const result=await request('replaceVariables',{variables:next,option:selected,expected});
      if(selected.type==='chat') context.variables=clone(result.value);
      else if(selected.type==='preset') context.presetVariables=clone(result.value);
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
  let presetWarmed = '';
  // getPresetList() returns preset *data* plus a name->index map, so keep them apart from the name list.
  let presetListData = [], presetListNames = [], presetListIds = [];
  const libraryPresets = () => context.presets || [];
  const presetRecord = value => libraryPresets().find(preset => preset.id === value || preset.name === value);
  const selectedPreset = () => libraryPresets().find(preset => preset.id === context.activePresetId);
  // PiuPiu duplicates the current preset name in getAllPresets and prefixes getPresetNames with "in_use".
  const getAllPresets = () => {
    const names = presetListNames.length ? presetListNames.slice() : libraryPresets().map(preset => preset.name), current = selectedPreset()?.name;
    return current ? [current, ...names] : names;
  };
  const getPresetNames = () => ['in_use', ...getAllPresets()];
  const presetKey = (value = '') => {
    if (value == null || value === '' || value === 'in_use') return context.activePresetId || selectedPreset()?.id || '';
    return presetRecord(value)?.id || String(value);
  };
  const presetNameOf = (value = '') => presetRecord(value)?.name || (value && value !== 'in_use' ? String(value) : selectedPreset()?.name || '');
  const presetData = (name = '') => {
    const key = presetKey(name);
    if (key && presetCache.has(key)) return presetCache.get(key);
    const alias = presetRecord(name)?.name || (name == null || name === '' || name === 'in_use' ? selectedPreset()?.name : '');
    return alias && presetCache.has(alias) ? presetCache.get(alias) : null;
  };
  const rememberPreset = (key, data) => {
    const copy = clone(data), record = presetRecord(key) || (copy && typeof copy === 'object' ? presetRecord(copy.id) : null);
    presetCache.set(key, copy);
    if (record) { presetCache.set(record.id, copy); presetCache.set(record.name, copy); }
    return clone(copy);
  };
  const dropPresetCache = (...keys) => {
    for (const key of keys) { const record = presetRecord(key); presetCache.delete(key); if (record) { presetCache.delete(record.id); presetCache.delete(record.name); } }
  };
  const fetchPreset = async name => {
    const key = presetKey(name);
    if (!key) throw new Error('当前会话未选择预设');
    return rememberPreset(key, await request('getPresetSettings', { name: key }));
  };
  const ensurePreset = async name => { const cached = presetData(name); return cached ? clone(cached) : fetchPreset(name); };
  const loadPreset = async name => {
    const key = presetKey(name);
    if (!key) throw new Error('当前会话未选择预设');
    const record = await request('loadPreset', { name: key });
    rememberPreset(record.name || key, record.data);
    if (record.id) presetCache.set(record.id, clone(record.data));
    return clone(record.data);
  };
  const getPresetSettings = name => {
    const data = presetData(name);
    if (data == null) throw new Error('预设尚未加载：' + (presetNameOf(name) || '未选择预设'));
    return clone(data);
  };
  const presetSampleKeys = ['temperature', 'top_p', 'top_k', 'frequency_penalty', 'presence_penalty', 'openai_max_tokens', 'max_tokens', 'seed', 'stop', 'n', 'min_p', 'top_a', 'repetition_penalty', 'temp_openai', 'top_p_openai', 'top_k_openai', 'frequency_penalty_openai', 'presence_penalty_openai', 'max_tokens_openai', 'freq_pen_openai', 'pres_pen_openai', 'repetition_penalty_openai', 'min_p_openai', 'top_a_openai', 'openai_max_context', 'names_behavior', 'reasoning_effort', 'squash_system_messages', 'stream_openai', 'wrap_in_quotes'];
  const presetSettings = data => {
    const settings = {};
    if (data && typeof data === 'object') for (const key of presetSampleKeys) if (data[key] !== undefined) settings[key] = clone(data[key]);
    return settings;
  };
  const presetOrder = data => {
    const groups = Array.isArray(data?.prompt_order) ? data.prompt_order : [];
    return groups.find(group => group && group.character_id === '100001' && Array.isArray(group.order)) ||
      groups.find(group => group && Array.isArray(group.order) && group.order.length) ||
      groups.find(group => group && Array.isArray(group.order)) || null;
  };
  const convertPresetPrompt = prompt => ({
    id: prompt.identifier ?? prompt.id, name: prompt.name ?? '', role: prompt.role ?? 'system', content: prompt.content ?? '',
    enabled: prompt.enabled !== false,
    position: { type: prompt.injection_position === 1 ? 'in_chat' : 'relative', depth: prompt.injection_depth ?? 0, order: prompt.injection_order ?? 100 },
    ...(prompt.extra && typeof prompt.extra === 'object' && !Array.isArray(prompt.extra) ? { extra: clone(prompt.extra) } : {}),
  });
  // Match TavernHelper.getPreset in PiuPiu: convert raw OpenAI presets into the exposed prompt shape.
  const convertPreset = data => {
    const source = data && typeof data === 'object' ? data : {}, prompts = Array.isArray(source.prompts) ? source.prompts : [];
    const byIdentifier = new Map();
    prompts.forEach(prompt => { const id = prompt?.identifier ?? prompt?.id; if (id != null && !byIdentifier.has(id)) byIdentifier.set(id, prompt); });
    const used = new Set(), selected = [], unused = [], order = presetOrder(source)?.order, entries = Array.isArray(order) ? order : [];
    for (const entry of entries) {
      if (!entry || entry.enabled === false) continue;
      const prompt = byIdentifier.get(entry.identifier ?? entry.id);
      if (!prompt || used.has(prompt)) continue;
      used.add(prompt); selected.push(convertPresetPrompt(prompt));
    }
    for (const prompt of prompts) if (!used.has(prompt)) unused.push(convertPresetPrompt(prompt));
    return { settings: presetSettings(source), prompts: selected, prompts_unused: unused, extensions: source.extensions && typeof source.extensions === 'object' ? clone(source.extensions) : {} };
  };
  const getPreset = name => convertPreset(getPresetSettings(name));
  const getCompletionPresetByName = name => { try { return getPresetSettings(name); } catch (_) { return undefined; } };
  const getActivePresetId = () => {
    const id = context.activePresetId, numeric = typeof id === 'string' && id.trim() !== '' ? Number(id) : id;
    if (typeof numeric === 'number' && Number.isFinite(numeric)) return numeric;
    return selectedPreset()?.name ?? (id ?? null);
  };
  const selectPreset = async nameOrId => {
    const requested = nameOrId == null ? '' : String(nameOrId);
    const result = await request('selectPreset', { id: requested });
    if (result.value === false) return false;
    if (result.activePresetId !== undefined) context.activePresetId = result.activePresetId;
    const name = selectedPreset()?.name || (requested && requested !== 'in_use' ? requested : '');
    await eventEmit('preset_changed', name, result.activePresetId);
    await eventEmit('oai_preset_changed_after', name);
    warmPreset();
    return result.value;
  };
  const refreshPresetList = async () => {
    try {
      const result = await request('getPresetList', {});
      if (result && Array.isArray(result.presets)) presetListData = clone(result.presets);
      if (result) {
        // New contract: names[]; fall back to preset_names (object) and then to a legacy names array.
        const names = Array.isArray(result.names) ? result.names
          : Array.isArray(result.preset_names) ? result.preset_names
          : Object.keys(result.preset_names || {});
        presetListNames = names.map(name => String(name));
        presetListIds = Array.isArray(result.ids) ? result.ids.map(id => String(id)) : [];
      }
      return result;
    } catch (_) { return null; }
  };
  // Mirrors PiuPiu's shape: preset data array + name->index object + current sampling settings.
  const getPresetList = () => ({
    presets: clone(presetListData),
    preset_names: Object.fromEntries(presetListNames.map((name, index) => [name, index])),
    settings: presetSettings(presetData()),
  });
  const isCurrentPreset = value => {
    const record = presetRecord(value);
    if (record) return record.id === context.activePresetId;
    // "in_use"/empty always address the active preset.
    if (value == null || value === '' || value === 'in_use') return !!selectedPreset();
    return selectedPreset()?.name === String(value);
  };
  const assertWritableName = (name, { allowInUse = false } = {}) => {
    const trimmed = String(name ?? '').trim();
    if (!trimmed) throw new Error('预设名称不能为空');
    if (trimmed === 'in_use' && !allowInUse) throw new Error('保存预设名称无效');
    return trimmed;
  };
  // Low-level write path: never swallows host errors, keeps caches/events in step.
  const presetCommit = async (method, name, body = {}, shownName) => {
    const wasCurrent = isCurrentPreset(name);
    const result = await request(method, { name, ...body });
    if (result && result.value === false) throw new Error('预设操作失败：' + method);
    dropPresetCache(name, body.newName);
    // Prefer the host's normalized document; fall back to what we sent when it does not echo data.
    const written = result && result.data !== undefined ? result.data : body.data;
    if (written !== undefined) rememberPreset(result?.name || body.newName || name, written);
    presetListData = []; presetListNames = []; presetListIds = []; presetWarmed = '';
    await refreshPresetList();
    // Keep the in-memory read path in sync with what was just written.
    if (method !== 'deletePreset') { try { await fetchPreset(result?.name || body.newName || name); } catch (_) {} }
    warmPreset();
    if (wasCurrent) { try { await eventEmit('preset_changed', shownName || presetNameOf(name), context.activePresetId); } catch (_) {} }
    return result;
  };
  const withData = data => data === undefined ? {} : { data };
  const createPreset = async (name, data, options) => {
    const target = String(name ?? '').trim();
    if (target === 'in_use' || getAllPresets().includes(target)) return false;
    assertWritableName(target);
    await presetCommit('createPreset', target, withData(data));
    return true;
  };
  const savePreset = async (name, data, options) => {
    const target = assertWritableName(name);
    await presetCommit('savePreset', target, withData(data));
  };
  const createOrReplacePreset = async (name, data, options) => {
    const target = assertWritableName(name, { allowInUse: true });
    // PiuPiu routes "in_use" through replacePreset: write the active preset, report nothing created.
    if (target === 'in_use') {
      const active = selectedPreset()?.name || context.activePresetId || 'in_use';
      await presetCommit('replacePreset', active, { data: data === undefined ? {} : data });
      return false;
    }
    const result = await presetCommit('savePreset', target, withData(data));
    return result?.created === true;
  };
  const replacePreset = async (name, data, options) => {
    if (data === undefined || data === null) throw new TypeError('预设数据格式无效');
    // An empty name means "the active preset"; resolve it locally so the host does not see "in_use".
    const target = String(name ?? '').trim() || selectedPreset()?.name || 'in_use';
    await presetCommit('replacePreset', target, { data });
  };
  const setPreset = async (name, data, options) => {
    const result = await presetCommit('setPreset', name, { data });
    return convertPreset(result?.data ?? data);
  };
  const deletePreset = async name => {
    const target = String(name ?? '').trim();
    if (!target || target === 'in_use' || !getAllPresets().includes(target)) return false;
    try { await presetCommit('deletePreset', target); return true; }
    catch (_) { return false; }
  };
  const renamePreset = async (name, newName, options) => {
    const from = String(name ?? '').trim(), to = String(newName ?? '').trim();
    if (!from || !to || from === 'in_use') return false;
    if (getAllPresets().includes(to)) return false;
    if (!getAllPresets().includes(from)) throw new Error('预设不存在：' + from);
    await presetCommit('renamePreset', from, { newName: to }, to);
    return true;
  };
  const updatePreset = async patch => {
    if (!patch || typeof patch !== 'object') return false;
    const name = patch.name ?? patch.identifier;
    if (!name && !selectedPreset()) return false;
    const body = { ...patch }; delete body.name; delete body.identifier;
    await presetCommit('updatePreset', name || selectedPreset()?.name, { patch: body });
  };
  const updatePresetWith = async (name, fn, options) => {
    if (typeof fn !== 'function') throw new TypeError('updatePresetWith 需要一个更新函数');
    const data = await fetchPreset(name);
    const updated = await fn(clone(data));
    if (!updated || typeof updated !== 'object') throw new TypeError('预设更新函数必须返回预设对象');
    await savePreset(name, updated);
    return updated;
  };
  // Fetch the active preset as soon as the runtime is installed; failures stay silent.
  const warmPreset = () => {
    const record = selectedPreset(), key = context.activePresetId ? String(context.activePresetId) + ':' + (record?.revision ?? '') : '';
    if (!key || presetData() || presetWarmed === key) return;
    presetWarmed = key;
    ensurePreset().catch(() => {});
  };
  // PiuPiu exposes six aliases; they all sync-return undefined and just request the UI.
  const openPresetManager = () => { request('openPresetManager', {}).catch(() => {}); };
  const presetManager = {
    apiId: 'openai', getAllPresets,
    getPresetNames, getActivePresetId,
    getSelectedPresetName: () => selectedPreset()?.name ?? '',
    getSelectedPreset: () => selectedPreset()?.name ?? '',
    getPresetSettings, getPreset, getCompletionPresetByName,
    findPreset: name => getAllPresets().includes(name) ? name : undefined,
    loadPreset, selectPreset, setActivePresetId: selectPreset,
    getPresetList, createPreset, createOrReplacePreset, deletePreset, renamePreset, replacePreset, updatePreset, updatePresetWith, setPreset, savePreset,
    open: openPresetManager, show: openPresetManager, openManager: openPresetManager,
    openPresetManager, showPresetManager: openPresetManager, openPromptManager: openPresetManager,
  };
  const getPresetManager = () => presetManager;
  const getContext = () => {
    const result = { chat: context.chat || [], name1: context.name1, name2: context.name2, chatId: context.sessionId, chatMetadata: { variables: getVariables() }, eventSource: { on: eventOn, removeListener: eventOff, once: eventOnce, emit: eventEmit }, saveChat: async () => true, saveMetadata: async () => replaceVariables(context.variables), getPresetManager,
      getPreset, getPresetSettings, getPresetNames, loadPreset,
      substituteParams: text => api.substituteParams(text),
      sendOpenAIRequest: async (_type, messages, signal) => {
        if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
        const content = await request('generate', { ordered_prompts: messages });
        if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
        return { choices: [{ message: { role: 'assistant', content } }] };
      },
    };
    // PiuPiu exposes the preset sampling block as context.chatCompletionSettings; omit it when nothing is cached.
    const data = presetData();
    if (data) result.chatCompletionSettings = presetSettings(data);
    return result;
  };
  const api = { getVariables, replaceVariables, insertOrAssignVariables, updateVariablesWith, getChatMessages, setChatMessages, getContext, eventOn, eventOff, eventOnce, eventEmit,
    getPresetVariables: () => getVariables({type:'preset'}), setPresetVariables: value => replaceVariables(value, {type:'preset'}),
    getWorldbookNames, loadWorldInfo, getWorldbook, saveWorldInfo, createWorldbook, deleteWorldbook, rebindCharWorldbooks,
    getPresetManager, getPresetNames, getAllPresets, loadPreset, ensurePreset, getPreset, getPresetSettings, getPresetList, getCompletionPresetByName,
    createPreset, createOrReplacePreset, deletePreset, renamePreset, replacePreset, setPreset, savePreset, updatePreset, updatePresetWith,
    getLoadedPresetName: () => selectedPreset()?.name ?? '', getActivePresetId, setActivePresetId: selectPreset,
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
  warmPreset();
  refreshPresetList();
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
  window.tavern_events={MESSAGE_UPDATED:'message_updated', MESSAGE_RECEIVED:'message_received', MESSAGE_SENT:'message_sent', CHAT_CHANGED:'chat_id_changed', GENERATION_STARTED:'generation_started', GENERATION_ENDED:'generation_ended', GENERATION_STOPPED:'generation_stopped', CHAT_COMPLETION_PROMPT_READY:'chat_completion_prompt_ready', GENERATE_AFTER_DATA:'generate_after_data', PRESET_CHANGED:'preset_changed', PRESET_DELETED:'preset_deleted', PRESET_RENAMED:'preset_renamed', PRESET_RENAMED_BEFORE:'preset_renamed_before', OAI_PRESET_CHANGED_AFTER:'oai_preset_changed_after', OAI_PRESET_CHANGED_BEFORE:'oai_preset_changed_before'};
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
    let presetListStale = false;
    for (const prior of context.presets || []) {
      const current = (next.presets || []).find(item => item.id === prior.id);
      if (!current || current.name !== prior.name || current.revision !== prior.revision) {
        presetCache.delete(prior.id); presetCache.delete(prior.name); presetListStale = true; presetWarmed = '';
      }
    }
    if (presetListStale) { presetListData = []; presetListNames = []; presetListIds = []; refreshPresetList(); }
    context = next;
    warmPreset();
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

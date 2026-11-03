/* Extracted PiuPiu 2.7.9 MVU helpers and schema hooks; host adapters below. */
(() => {
const __clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const eventSource = window.eventSource;
const __rpc = (method, args) => window.__xujingRequest(method, { args })
  .then(value => value && typeof value === 'object' && !Array.isArray(value) && 'value' in value ? value.value : value);
const __isPlainObject = value => !!value && typeof value === 'object' && !Array.isArray(value);
  // TavernHelper uses lodash mergeWith: objects merge recursively while an
  // array supplied by the right-hand side replaces the old array wholesale.
  const __mergeReplacingArrays = (target, source) => {
    const output = __isPlainObject(target) ? __clone(target) : {};
    if (!__isPlainObject(source)) return Array.isArray(source) ? __clone(source) : output;
    for (const [key, value] of Object.entries(source)) {
      if (Array.isArray(value)) output[key] = __clone(value);
      else if (__isPlainObject(value)) output[key] = __mergeReplacingArrays(output[key], value);
      else output[key] = __clone(value);
    }
    return output;
  };
  const __stringHash = (value, seed = 0) => {
    value = typeof value === 'string' ? value : ''; let h1 = 0xdeadbeef ^ seed; let h2 = 0x41c6ce57 ^ seed;
    for (let index = 0; index < value.length; index += 1) { const code = value.charCodeAt(index); h1 = Math.imul(h1 ^ code, 2654435761); h2 = Math.imul(h2 ^ code, 1597334677); }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
  };
  const __path = value => Array.isArray(value) ? value.map(String) : String(value || '')
    .replace(/^\/+/, '').replace(/~1/g, '/').replace(/~0/g, '~')
    .replace(/\[(\d+)\]/g, '.$1').split(/[./]/).filter(Boolean).filter((part, index) => !(index === 0 && part === 'stat_data'));
  const __get = (root, path) => __path(path).reduce((value, key) => value == null ? undefined : value[key], root);
  const __has = (root, path) => {
    const parts = __path(path); let value = root;
    for (const key of parts) { if (value == null || !Object.prototype.hasOwnProperty.call(value, key)) return false; value = value[key]; }
    return true;
  };
  const __set = (root, path, next) => {
    const parts = __path(path); if (!parts.length) return next;
    let value = root;
    for (let index = 0; index < parts.length - 1; index += 1) {
      const key = parts[index]; if (!value[key] || typeof value[key] !== 'object') value[key] = /^\d+$/.test(parts[index + 1]) ? [] : {};
      value = value[key];
    }
    value[parts[parts.length - 1]] = __clone(next); return root;
  };
  const __del = (root, path, index) => {
    const parts = __path(path); if (!parts.length) return;
    const parent = __get(root, parts.slice(0, -1)); const key = parts[parts.length - 1];
    const target = parts.length === 1 ? root : parent;
    if (target == null) return;
    if (index !== undefined) {
      const collection = target[key];
      if (Array.isArray(collection)) collection.splice(Number(index), 1);
      else if (collection && typeof collection === 'object') delete collection[String(index)];
    } else if (Array.isArray(target)) target.splice(Number(key), 1); else delete target[key];
  };
  const __decodeArg = value => {
    if (typeof value !== 'string') return __clone(value);
    try { return JSON.parse(value); } catch (_) {
      const first = value.charAt(0); const last = value.charAt(value.length - 1);
      return value.length >= 2 && first === last && (first === "'" || first === '"' || first === String.fromCharCode(96)) ? value.slice(1, -1) : value;
    }
  };
  function __materialize(command) {
    const args = Array.isArray(command.args) ? command.args.map(__decodeArg) : [];
    const type = String(command.type || command.op || 'set').toLowerCase();
    return {
      type, path: args[0] ?? command.path, value: args[1] ?? command.value,
      expectedValue: type === 'set' && args.length >= 3 ? args[1] : command.expectedValue,
      index: (type === 'insert' || type === 'assign') && args.length >= 3 ? args[1] : command.index,
      targetPath: type === 'move' ? (args[1] ?? command.targetPath) : command.targetPath,
      finalValue: type === 'set' && args.length >= 3 ? args[2] : ((type === 'insert' || type === 'assign') && args.length >= 3 ? args[2] : (args[1] ?? command.value))
    };
  }
  function __applyCommand(stat, raw) {
    const command = __materialize(raw); const parts = __path(command.path);
    if (parts.some(part => String(part).startsWith('_'))) throw new Error('readonly MVU path');
    const old = __get(stat, parts); const type = command.type;
    if (type === 'set' || type === 'replace') {
      if (!__has(stat, parts)) throw new Error('missing set path');
      if (command.expectedValue !== undefined && JSON.stringify(old) !== JSON.stringify(command.expectedValue)) throw new Error('set condition mismatch');
      __set(stat, parts, command.finalValue); return;
    }
    if (type === 'add' || type === 'delta') {
      const delta = Number(command.finalValue); if (!Number.isFinite(delta)) throw new Error('invalid delta');
      if (Array.isArray(old) && old.length === 2 && typeof old[0] === 'number' && typeof old[1] === 'string') __set(stat, parts, [old[0] + delta, old[1]]);
      else if (typeof old === 'number') __set(stat, parts, old + delta); else throw new Error('invalid add target');
      return;
    }
    if (type === 'insert' || type === 'assign') {
      if (Array.isArray(old)) old.splice(command.index === '-' || command.index === undefined ? old.length : Number(command.index), 0, __clone(command.finalValue));
      else if (old && typeof old === 'object') {
        if (command.index !== undefined) old[String(command.index)] = __clone(command.finalValue);
        else Object.assign(old, __clone(command.finalValue));
      } else if (!__has(stat, parts)) __set(stat, parts, command.finalValue); else throw new Error('invalid insert target');
      return;
    }
    if (type === 'delete' || type === 'remove' || type === 'unset') { __del(stat, parts, command.index); return; }
    if (type === 'move') { const moved = __clone(old); __del(stat, parts); __set(stat, command.targetPath, moved); return; }
    throw new Error('unsupported command ' + type);
  }

  
const __mvuEvents = {
    VARIABLE_INITIALIZED: 'mag_variable_initialized', VARIABLE_UPDATE_STARTED: 'mag_variable_update_started',
    COMMAND_PARSED: 'mag_command_parsed', SINGLE_VARIABLE_UPDATED: 'mag_variable_updated',
    VARIABLE_UPDATE_ENDED: 'mag_variable_update_ended', BEFORE_MESSAGE_UPDATE: 'mag_before_message_update'
  };
  
const __mvu = {
    events: __mvuEvents,
    // MagVarUpdate delegates these APIs directly to TavernHelper variables.
    // Their default scope is chat; message is selected only when requested.
    getMvuData: option => window.getVariables(option || { type: 'chat' }),
    replaceMvuData: (data, option) => window.replaceVariables(data, option || { type: 'chat' }),
    getCurrentMvuData: () => window.getVariables({ type: 'message' }),
    replaceCurrentMvuData: data => window.replaceVariables(data, { type: 'message' }),
    parseMessage: (message, oldData) => __rpc('parseMvuMessage', [message, oldData]),
    reloadInitVar: data => __rpc('reloadMvuInitVar', [data]),
    getMvuVariable: (data, path, option) => {
      const category = option && option.category === 'display' ? 'display_data' : option && option.category === 'delta' ? 'delta_data' : 'stat_data';
      const value = __get((data && data[category]) || {}, path);
      const fallback = value === undefined && option ? option.default_value : value;
      return Array.isArray(fallback) && fallback.length === 2 && typeof fallback[1] === 'string' ? fallback[0] : fallback;
    },
    setMvuVariable: async (data, path, value, option) => {
      const stat = data && data.stat_data; if (!stat || !__has(stat, path)) return false;
      const old = __get(stat, path); const next = Array.isArray(old) && old.length === 2 ? [__clone(value), old[1]] : __clone(value);
      __set(stat, path, next);
      if (option && option.is_recursive) await eventSource.emit(__mvuEvents.SINGLE_VARIABLE_UPDATED, stat, path, Array.isArray(old) && old.length === 2 ? old[0] : old, value);
      return true;
    },
    getRecordFromMvuData: (data, category) => { const key = category === 'display' ? 'display_data' : category === 'delta' ? 'delta_data' : 'stat_data'; return data && data[key]; },
    isDuringExtraAnalysis: () => false
  };
  Object.defineProperty(window, 'Mvu', {
    configurable: false,
    enumerable: true,
    get: () => __mvu,
    set: () => {}
  });

  let __registeredSchema = null;
  window.registerMvuSchema = function(schema) {
    __registeredSchema = schema && window.z && window.z.ZodObject && schema instanceof window.z.ZodObject && window.z.looseObject
      ? window.z.looseObject(schema.shape)
      : schema;
  };
  // TavernHelper's generic schema API and MVU's legacy schema hook share the
  // same Zod boundary in PiuPiu. Keep the public name available to portable
  // scripts instead of requiring a card-specific Mvu branch.
  window.registerVariableSchema = function(schema, _option) {
    return window.registerMvuSchema(schema);
  };
  eventSource.on('mag_variable_initialized', variables => {
    if (!__registeredSchema || !variables) return;
    const result = __registeredSchema.safeParse(variables.stat_data || {});
    if (result.success) variables.stat_data = Object.assign({}, variables.stat_data || {}, result.data);
  });
  eventSource.on('mag_command_parsed_for_zod', (variables, commands) => {
    if (!__registeredSchema || !variables || !Array.isArray(commands)) return;
    for (let index = 0; index < commands.length;) {
      const candidate = __clone(variables.stat_data || {});
      try {
        __applyCommand(candidate, commands[index]);
        const result = __registeredSchema.safeParse(candidate);
        if (result.success) {
          variables.stat_data = Object.assign({}, variables.stat_data || {}, result.data);
          commands.splice(index, 1);
          continue;
        }
      } catch (_) {}
      index += 1;
    }
  });
  eventSource.on('mag_command_parsed_ended_for_zod', (_variables, commands) => {
    if (__registeredSchema && Array.isArray(commands)) commands.length = 0;
  });
  eventSource.on('mag_variable_update_ended_for_zod', variables => {
    if (!__registeredSchema || !variables) return;
    variables.schema = '没有用别管这个'; delete variables.display_data; delete variables.delta_data;
  });

  
})();

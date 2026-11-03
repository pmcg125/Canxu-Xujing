// SillyTavern root module compatibility surface used by preset extensions.
export const displayVersion = 'SillyTavern 1.13.4 (PiuPiu compatibility)';

// SillyTavern replaces this object for an active streaming generation. PiuPiu
// exposes its stream through TavernHelper events, so no processor exists while
// idle; extensions already treat null as the upstream idle state.
export let streamingProcessor = null;

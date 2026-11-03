/* PiuPiu EJS wrapper semantics adapted to the Android host; EJS engine is retained. */
(() => {
  'use strict';
  const copy=value=>JSON.parse(JSON.stringify(value || {}));
  const parts=path=>String(path).replace(/\[(\d+)\]/g,'.$1').split('.').filter(Boolean);
  function parseDirectives(text) {
    const directives={preprocessing:false,private:false,renderBefore:false,renderAfter:false,messageFormatting:false,iframe:false};
    const body=[]; let beginning=true;
    for(const line of String(text || '').split(/\r?\n/)) {
      const trimmed=line.trim();
      if(beginning && trimmed.startsWith('@@')) {
        const [name,...args]=trimmed.slice(2).split(/\s+/), key=name.toLowerCase();
        const flags={preprocessing:'preprocessing',private:'private',render_before:'renderBefore',render_after:'renderAfter',message_formatting:'messageFormatting'};
        if(flags[key]) { directives[flags[key]]=true; continue; }
        if(key==='iframe') {directives.iframe=true;directives.iframeTitle=args.join(' ').trim() || undefined;continue;}
        if(key==='if') {directives.condition=args.join(' ').trim();continue;}
        if(['activate','dont_activate','generate_before','generate_after','dont_preload','initial_variables','always_enabled','only_preload'].includes(key)) continue;
      }
      if(trimmed) beginning=false;
      body.push(line);
    }
    return {body:body.join('\n'),directives};
  }
  async function render(text,context={},options={}) {
    const {body,directives}=parseDirectives(text), variables=copy(context.variables);
    if(!/<%[=_-]?/.test(body) && !directives.condition) return {content:body,directives,variables};
    const getvar=context.getvar || ((path,option)=>{
      let value=variables; for(const key of parts(path)) value=value?.[key];
      return value===undefined ? option?.defaults : value;
    });
    const setvar=context.setvar || ((path,value)=>{
      const keys=parts(path); if(!keys.length) throw Error('Empty variable path');
      let target=variables;
      for(const key of keys) if(['__proto__','constructor','prototype'].includes(key)) throw Error('Invalid variable path');
      keys.forEach((key,index)=>{if(index===keys.length-1) target[key]=value;else target=target[key] ||= {};});
      return value;
    });
    let template=directives.condition ? '<% if ('+directives.condition+') { %>'+body+'<% } %>' : body;
    if(directives.private) template='<% { %>'+template+'<% } %>';
    try {
      const content=await window.ejs.render(template,{...context,variables,getvar,setvar,user:context.user || '',char:context.char || '',console,JSON,Math,Date},{async:true,rmWhitespace:false,...options.ejs});
      return {content:String(content || ''),directives,variables};
    } catch(error) {
      if(options.throwOnError) throw error;
      console.warn('[Xujing EJS] Template failed-open',context.entryName || context.entryId || 'inline',error);
      return {content:body,directives,variables:copy(context.variables),error:String(error)};
    }
  }
  window.EjsTemplate={render,parseDirectives:text=>parseDirectives(text).directives,
    evaluate:(text,context={},options={})=>render(text,context,{throwOnError:true,ejs:options.options || options}),
    getSyntaxErrorInfo:text=>{try{window.ejs.compile(String(text || ''),{async:true});return '';}catch(error){return error.message;}}
  };
  // Display rendering is local. Authors explicitly commit result.variables through
  // replaceVariables; merely scrolling a message must not repeat stored side effects.
  window.__xujingRenderCard=async text=>{
    const result=await render(text,{variables:window.getVariables(),user:window.SillyTavern.getContext().name1,char:window.SillyTavern.getContext().name2});
    const container=document.createElement('template');container.innerHTML=result.content;
    const scripts=[...container.content.querySelectorAll('script')].map(old=>{const marker=document.createComment('card-script');old.replaceWith(marker);return {old,marker};});
    document.body.append(container.content);
    for(const {old,marker} of scripts) {
      const script=document.createElement('script');
      for(const attr of old.attributes) script.setAttribute(attr.name,attr.value);
      script.textContent=old.textContent;
      if(script.src || script.type==='module') {
        await new Promise((resolve,reject)=>{script.onload=resolve;script.onerror=()=>reject(Error('Card script failed to load'));marker.replaceWith(script);});
      } else marker.replaceWith(script);
    }
    await window.__xujingCardReady();
  };
})();

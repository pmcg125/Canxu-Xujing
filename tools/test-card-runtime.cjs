const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const root=path.resolve(__dirname,'../app/src/main/assets');
async function main(){
  const initial={sessionId:'s',messageId:'m',variables:{stat_data:{hp:2},unknown:'retain'},chat:[{id:'m',message_id:0,swipe_id:0,variables:{stat_data:{hp:1}}}]};
  let rejectWrite=false, hostVariables=structuredClone(initial.variables);
  const w={console,URL,setTimeout,clearTimeout,DOMException,__XUJING_CONTEXT__:initial,
    document:{addEventListener(){}},XujingBridge:{request(runtimeToken,id,method,raw){
      const p=JSON.parse(raw);
      queueMicrotask(()=>{
        // Bridge calls that are not session-variable writes must not be answered with the variable payload.
        if(method==='broadcastRuntimeEvent') return w.__xujingReply(id,{value:true,args:p.args});
        if(method==='parseMvuMessage') return w.__xujingReply(id,{value:{parsed:'QA_PARSE_OK'}});
        if(rejectWrite) return w.__xujingReply(id,{error:'disk failure'});
        if(p.option?.type==='chat'){
          if(p.expected!==undefined && JSON.stringify(p.expected)!==JSON.stringify(hostVariables)) return w.__xujingReply(id,{error:'stale'});
          hostVariables=p.variables;
        }
        w.__xujingReply(id,{value:p.variables});
      });
    }}};
  w.window=w;vm.createContext(w);
  for(const file of ['card-vendors/zod.js','card-vendors/ejs.js','card-runtime.js','card-vendors/mvu.js','card-template.js']) vm.runInContext(fs.readFileSync(path.join(root,file),'utf8'),w,{filename:file});
  assert.equal(w.ejs.VERSION,'3.1.10');
  assert.equal(w.z.number().min(0).safeParse(-1).success,false);
  const rendered=await w.EjsTemplate.evaluate('@@if getvar("show")\n<%= await Promise.resolve(user) %>:<%- getvar("html") %><% setvar("score", 4) %>',{user:'A&B',variables:{show:true,html:'<b>ok</b>',score:1}});
  assert.equal(rendered.content,'A&amp;B:<b>ok</b>'); assert.equal(rendered.variables.score,4);
  assert.equal(hostVariables.stat_data.hp,2); // rendering never commits implicitly
  const failed=await w.EjsTemplate.render('<% setvar("score",9); throw Error("expected") %>',{variables:{score:1}});
  assert.equal(failed.variables.score,1);assert.ok(failed.error);
  await assert.rejects(()=>w.EjsTemplate.evaluate('<%= missing.value %>'),/missing/);
  assert.equal((await w.EjsTemplate.render('@@if false\nhidden')).content,'');
  assert.throws(()=>w.getVariables({type:'extension'}),/作用域/);
  assert.equal(JSON.stringify(w.getVariables({type:'global'})),'{}');
  await w.replaceVariables({scopeProbe:1},{type:'global'});
  assert.equal(w.getVariables({type:'global'}).scopeProbe,1);
  assert.equal(w.Mvu.getCurrentMvuData().stat_data.hp,1);
  const data={stat_data:{hp:[2,'health']}};
  assert.equal(await w.Mvu.setMvuVariable(data,'hp',3),true);
  assert.deepEqual(Array.from(data.stat_data.hp),[3,'health']);assert.equal(w.Mvu.getMvuVariable(data,'hp'),3);
  assert.equal(hostVariables.stat_data.hp,2); // original setter changes its argument only
  const schema=vm.runInContext('z.object({hp:z.coerce.number().min(0),mana:z.number().default(5)})',w);
  w.registerMvuSchema(schema);
  const initialized={stat_data:{hp:'7',other:'keep'}};
  await w.eventEmit(w.Mvu.events.VARIABLE_INITIALIZED,initialized);
  assert.equal(initialized.stat_data.hp,7);assert.equal(initialized.stat_data.mana,5);assert.equal(initialized.stat_data.other,'keep');
  const commands=[{type:'set',args:['hp',8]},{type:'set',args:['hp',-1]}];
  await w.eventEmit('mag_command_parsed_for_zod',initialized,commands);
  assert.equal(initialized.stat_data.hp,8);assert.equal(commands.length,1);
  await w.eventEmit('mag_command_parsed_ended_for_zod',initialized,commands);assert.equal(commands.length,0);
  let events=0;w.eventOn(w.Mvu.events.VARIABLE_UPDATE_ENDED,()=>events++);
  rejectWrite=true;
  await assert.rejects(()=>w.replaceVariables({stat_data:{hp:9}}),/disk failure/);
  assert.equal(w.getVariables().stat_data.hp,2);assert.equal(events,0);
  rejectWrite=false;await w.Mvu.replaceMvuData({stat_data:{hp:6}});
  assert.equal(w.getVariables().stat_data.hp,6);assert.equal(events,1);
  await w.Mvu.replaceCurrentMvuData({stat_data:{hp:4}});
  assert.equal(w.Mvu.getCurrentMvuData().stat_data.hp,4);assert.equal(hostVariables.stat_data.hp,6);
  w.__xujingUpdate(initial); // stateSynchronized is only set by the host snapshot update
  assert.equal((await w.waitGlobalInitialized('Mvu')),w.Mvu);
  const qaParsed=await w.Mvu.parseMessage('text',{});
  assert.equal(qaParsed.parsed,'QA_PARSE_OK'); // host now implements parseMvuMessage; __rpc unwraps {value}
  console.log('Card runtime checks passed: original vendors/MVU hooks, EJS, scopes and failed writes.');
}
main().catch(error=>{console.error(error);process.exitCode=1;});

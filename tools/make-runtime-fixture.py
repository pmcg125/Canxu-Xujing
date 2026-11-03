"""Build the synthetic EJS/Zod/MVU host test backup. No external API needed."""
from pathlib import Path
import json, zipfile

body = '''<div id="ejs-result">EJS:<%= user %>:<%= getvar('label') %></div>
<div id="runtime-status">STARTING</div>
<script type="module">
const status=document.getElementById('runtime-status');
try {
 await waitGlobalInitialized('Mvu');
 if(document.getElementById('ejs-result').textContent!=='EJS:RuntimeUser:A&B') throw Error('ejs-html');
 if(z.number().min(0).safeParse(-1).success) throw Error('zod-invalid');
 const existing=Mvu.getMvuData();
 if(!existing.qaComplete) {
  registerMvuSchema(z.object({hp:z.coerce.number().min(0),mana:z.number().default(5)}));
  const data={...existing,stat_data:{hp:'7',extra:'preserved'}};
  await eventEmit(Mvu.events.VARIABLE_INITIALIZED,data);
  if(data.stat_data.hp!==7 || data.stat_data.mana!==5 || data.stat_data.extra!=='preserved') throw Error('schema-init');
  data.qaComplete=true;
  await Mvu.replaceMvuData(data);
  await Mvu.replaceCurrentMvuData({stat_data:{hp:3}});
 }
 if(Mvu.getMvuData().stat_data.hp!==7 || Mvu.getCurrentMvuData().stat_data.hp!==3) throw Error('scope-or-persistence');
 const open=String.fromCharCode(60,37), close=String.fromCharCode(37,62);
 const result=await EjsTemplate.evaluate(open+' setvar("value",4) '+close+open+'= await Promise.resolve(getvar("value")) '+close,{variables:{value:1}});
 if(result.content!=='4' || result.variables.value!==4 || getVariables().value!==undefined) throw Error('template-local');
 const local={stat_data:{hp:[1,'health']}};
 await Mvu.setMvuVariable(local,'hp',2);
 if(local.stat_data.hp[0]!==2 || Mvu.getMvuData().stat_data.hp!==7) throw Error('local-setter');
 status.textContent='RUNTIME_PASS';
} catch(error) {status.textContent='RUNTIME_ERROR '+error.message;}
</script>'''
state={
 'schemaVersion':1,
 'roles':[{'id':'runtime-role-b','name':'Runtime QA','firstMessage':body}],
 'sessions':[{'id':'runtime-session-b','title':'Runtime QA EJS Zod B','roleIds':['runtime-role-b'],'userName':'RuntimeUser',
  'variables':{'label':'A&B'},'messages':[{'id':'runtime-message-b','role':'assistant','speakerId':'runtime-role-b','name':'Runtime QA','content':body}]}],
 'preferences':{'allowCardScripts':True}
}
destination=Path(__file__).parent/'fixtures'/'card-runtime-ejs-zod-qa.zip'
destination.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as archive:
 archive.writestr('manifest.json',json.dumps({'appId':'app.xujing','version':1}))
 archive.writestr('xujing-state.json',json.dumps(state,ensure_ascii=False))
print(destination)

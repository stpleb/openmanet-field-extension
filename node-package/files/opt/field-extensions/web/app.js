'use strict';
// Field Extension web UI v2: RC1 dashboard layout, OpenMANET colours. Plain JS, offline.
const shownVer=v=>v?String(v).replace(/^\d+:/,''):v;   // opkg epoch ('1:') is not for people
const $=id=>document.getElementById(id);
function el(tag,text,className){const n=document.createElement(tag);if(text!==undefined&&text!==null)n.textContent=text;if(className)n.className=className;return n;}
const has=v=>v!==null&&v!==undefined;
const join=(...a)=>a.filter(x=>has(x)&&x!=='').join(' · ');
async function getJSON(path,ms=60000){const r=await fetch('/api/field/'+path,{signal:AbortSignal.timeout(ms)});if(!r.ok)throw Error(r.status);return r.json();}
let summary=null,net=null,sys=null;
// The OpenMANET page (openmanetd, "Mesh Operator Terminal") is on the same node at :8080; / only redirects to LuCI.
$('openmanet').href=location.protocol+'//'+location.hostname+':8080/';

// ---- Writes: Bearer token from POST /api/field/login. The node remembers this device for
// 30 days of use, so the token is kept in this browser too (cleared on sign-out or a 401).
const TOKEN_KEY='fe-token';
let token=(()=>{try{return localStorage.getItem(TOKEN_KEY);}catch{return null;}})(),poll=null;
function keepToken(t){token=t;try{t?localStorage.setItem(TOKEN_KEY,t):localStorage.removeItem(TOKEN_KEY);}catch{}}
async function post(path,payload,ms=30000){const r=await fetch('/api/field/'+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(payload),signal:AbortSignal.timeout(ms)});let j={};try{j=await r.json();}catch{}if(r.status===401&&path!=='login')keepToken(null);return {status:r.status,body:j};}
function loginForm(box,then){
 box.querySelector('form.login')?.remove();
 const f=el('form',undefined,'login');
 const ul=el('label',undefined,'field');const u=el('input');u.value='root';u.autocomplete='username';u.name='username';ul.append(el('span','Username'),u);
 const pl=el('label',undefined,'field');const p=el('input');p.type='password';p.autocomplete='current-password';p.name='password';pl.append(el('span','Node password'),p);
 const go=el('button','Sign in');go.type='submit';const cancel=el('button','Cancel','ghost');cancel.type='button';
 const msg=el('p','Sign in with the node password (same as the OpenMANET web page).','note');
 const btns=el('div',undefined,'btns');btns.append(go,cancel);f.append(msg,ul,pl,btns);
 cancel.addEventListener('click',()=>f.remove());
 f.addEventListener('submit',async e=>{e.preventDefault();go.disabled=true;go.textContent='Signing in…';msg.textContent='Checking the password with the node…';msg.className='note';
  // 12 s, not the 30 s write timeout: a slow mesh link must not leave a dead button behind.
  let r;try{r=await post('login',{username:u.value,password:p.value,remember:true,device:'Web '+(navigator.platform||'browser')},12000);}catch{r={body:{error:'No answer from the node. Check the Wi-Fi link and try again.'}};}p.value='';
  go.textContent='Sign in';
  if(r.body.ok){keepToken(r.body.token);f.remove();then();}else{msg.textContent=r.body.error||'Sign-in failed';msg.className='warn';go.disabled=false;}});
 box.append(f);p.focus();
}
// Run a write and hand the result to done(); without a session, show the login
// form in `box` and run it after sign-in (Cancel simply drops it).
async function act(box,path,payload,out,done){
 const ask=()=>{out.textContent='Sign in to make changes.';out.className='note';loginForm(box,go);};
 const go=async()=>{let r;try{r=await post(path,payload);}catch{r={status:0,body:{error:'Node not reachable. Check the Wi-Fi link.'}};}
  if(r.status===401)return ask();done(r);};
 if(!token)return ask();
 await go();
}

// ---- Header and tiles
const LED_COLOURS={magenta:'#b56cff',green:'#00e676',cyan:'#00e5ff',red:'#ff3b4d',amber:'#ffb300',blue:'#4d8bff',white:'#ffffff'};
const LED_MEANING={MESH_AND_INTERNET:'Mesh + internet',MESH_ONLY:'Mesh only',INTERNET_ONLY:'Internet only',NO_LINKS:'No mesh, no internet',
 BOOTING:'Starting',INITIALIZING:'Starting',FAULT:'Fault',BATTERY_CRITICAL:'Battery critical (under 10%)',BATTERY_LOW:'Battery low (under 20%)',
 SHUTTING_DOWN:'Shutting down',ADMIN:'Maintenance: being updated or worked on, don\'t unplug',LORA_ONLY:'LoRa only'};
function fmtUptime(u){const s=parseFloat(String(u||'').split(' ')[0]);if(!Number.isFinite(s))return null;const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return (d?d+'d ':'')+h+'h '+m+'m';}
function renderHeader(ok){
 const s=summary||{};const shown=s.identity?.callsign||s.node;$('node').textContent=shown||'Node';document.title=(shown?shown+' · ':'')+'Field Extension';
 $('uptime').textContent=fmtUptime(sys?.uptime)||'--';
 const n=s.mesh?.nodes;$('nodes').textContent=has(n)?`${n} node${n===1?'':'s'} on mesh`:'Mesh: unavailable';
 $('health').className='health '+(ok?'ok':'bad');$('health-text').textContent=ok?'System Online':'Not answering';
}
// tone: ok | warn | bad | info | muted -> OpenMANET palette
function tile(badge,label,value,tone,subs){
 const c=el('div',undefined,'card');const r=el('div',undefined,'card-row');
 const b=el('div',badge,'icon '+tone);b.setAttribute('aria-hidden','true');
 const t=el('div',undefined,'card-text');t.append(el('div',label,'label'),el('div',has(value)?value:'--','value mono '+tone));
 for(const s of subs||[])if(has(s)&&s!=='')t.append(el('div',s,'subvalue'));
 r.append(b,t);c.append(r);$('tiles').append(c);
}
function ifaceAddr(name){const i=net?.parsed?.interfaces?.find(x=>x.name===name);return i?.addresses?.find(a=>!a.includes(':'))||i?.addresses?.[0]||null;}
function meshIP(){for(const n of ['bat0','br-ahwlan','br-lan']){const a=ifaceAddr(n);if(a)return {name:n,addr:a};}return null;}
function backhaulType(dev){if(!dev)return null;if(/^(eth|end|lan|wan)/.test(dev))return 'Ethernet';if(/^(wwan|usb|rmnet|ppp)/.test(dev))return 'Cellular';if(/sta/.test(dev))return 'Wi-Fi client';if(/^(bat|br-ahwlan)/.test(dev))return 'Mesh gateway';return dev;}
function hm(min){return min>=60?Math.floor(min/60)+' h '+String(min%60).padStart(2,'0')+' min':min+' min';}
function timeLeft(e,ext){if(!e)return null;
 if(ext)return has(e.time_to_full_min)?'Full in about '+hm(e.time_to_full_min):null;
 return has(e.time_left_min)?'About '+hm(e.time_left_min)+' left'+(e.method==='typical'?' (typical load)':''):null;}
function renderTiles(){
 $('tiles').replaceChildren();const s=summary||{};
 const p=s.power||{};
 if(p.available){const pct=p.battery_percent,ext=p.external_power===true;
  tile('B','Battery',has(pct)?pct+'%':null,ext?'ok':!has(pct)?'muted':pct<20?'bad':pct<50?'warn':'ok',
   [join(has(p.battery_voltage)?p.battery_voltage.toFixed(2)+' V':null,ext?(p.charging?'Charging':'External power'):p.state),
    'Auto power-on: '+(p.auto_power_on===true?'On':p.auto_power_on===false?'Off':'Unknown'),
    timeLeft(p.estimate,ext),
    join(p.model,p.percent_source==='calibrated'?'calibrated %':null)]);}
 else tile('B','Battery','Unavailable','muted',['No battery monitor detected']);
 const g=s.gps||{};
 tile('G','Physical GPS',g.available?(g.state==='FIX'?'Fix':g.state||'Unknown'):'Not detected',g.state==='FIX'?'ok':g.available?'warn':'muted',[g.message||(g.available?null:'No GPS receiver')]);
 const m=s.mesh||{},nb=m.neighbors||[];
 tile('M','Mesh Neighbors',has(m.nodes)?String(nb.length):'Unavailable',!has(m.nodes)?'muted':nb.length?'ok':'warn',
  has(m.nodes)?[nb.length?`${m.nodes} nodes on the mesh`:'No direct neighbors',...nb.slice(0,3).map(x=>join(x.mac,x.transport,has(x.throughput_mbps)?x.throughput_mbps+' Mb/s':null))]:['Mesh routing not answering']);
 const i=s.internet||{};
 tile('I','Internet',i.available?'Online':'Offline',i.available?'info':'warn',[i.available?'Default route present':'No upstream route']);
 const mip=meshIP();
 tile('IP','Mesh IP',mip?mip.addr.split('/')[0]:null,mip?'plain':'muted',[mip?join('/'+mip.addr.split('/')[1],mip.name):'Waiting for network reading','batman-adv']);
 const h=s.halow||{};
 tile('RF','HaLow Radio',h.available?(has(h.frequency_mhz)?h.frequency_mhz.toFixed(1)+' MHz':h.state):'Not detected',h.state==='ONLINE'?'ok':h.available?'warn':'muted',
  h.available?['Bandwidth: '+(has(h.bandwidth_mhz)?h.bandwidth_mhz+' MHz':'--')+(has(h.primary_bandwidth_mhz)?` (${h.primary_bandwidth_mhz} MHz primary)`:''),'HaLow: '+(h.state||'--')+(h.interface?' on '+h.interface:'')]:[]);
 const ap=net?.parsed?.access_point;
 tile('AP','EUD Wi-Fi',ap?ap.ssid:net?'No access point':null,ap?'info':'muted',ap?[join(has(ap.channel)?'Channel '+ap.channel:null,has(ap.width_mhz)?ap.width_mhz+' MHz':null),ap.name]:['Phones and ATAK connect here']);
 const dr=net?.parsed?.default_route;
 tile('BH','Backhaul',dr?backhaulType(dr.interface):net?'None':null,!dr?'muted':nb.length?'mesh':'info',
  dr?[nb.length?'Mesh + internet (purple light)':'Internet only (cyan light)','Interface: '+dr.interface,'IP: '+(ifaceAddr(dr.interface)||'--'),'Gateway: '+(dr.gateway||'--')]:['No default route (mesh only)']);
}

// ---- Status LED panel (orb + controls, built once and updated in place)
let ctl=null;
function pressed(b,on){b.setAttribute('aria-pressed',on?'true':'false');}
function buildLed(){
 const box=$('led-controls');box.replaceChildren();
 const out=el('p','','note');out.setAttribute('aria-live','polite');
 const lab=el('div','LED Control','control-label');lab.id='lbl-ledctl';
 const row=el('div',undefined,'toggle-row');row.setAttribute('role','group');row.setAttribute('aria-labelledby','lbl-ledctl');
 const on=el('button','ON','power'),off=el('button','OFF','power');on.type=off.type='button';row.append(on,off);
 const blab=el('div','Brightness','control-label');blab.id='lbl-bright';const bval=el('div','--','brightness-value mono');
 const seg=el('div',undefined,'brightness-row');seg.setAttribute('role','group');seg.setAttribute('aria-labelledby','lbl-bright');const levels={};
 for(const v of [10,25,50,75,100]){const b=el('button',v+'%','bright-btn mono');b.type='button';levels[v]=b;seg.append(b);
  b.addEventListener('click',async()=>{const all=seg.querySelectorAll('button');all.forEach(x=>x.disabled=true);
   await act(box,'led',{brightness:v},out,r=>{if(r.body.ok){out.textContent=`Brightness set to ${v}%.`;out.className='note ok';if(summary?.led)summary.led.brightness=v;updateLed();}else{out.textContent=r.body.error||'Change failed';out.className='warn';}});
   all.forEach(x=>x.disabled=false);});}
 const set=async want=>{on.disabled=off.disabled=true;await act(box,'led',{enabled:want},out,r=>{if(r.body.ok){out.textContent=`Status light ${want?'on':'off'}.`;out.className='note ok';if(summary?.led)summary.led.enabled=want;updateLed();}else{out.textContent=r.body.error||'Change failed';out.className='warn';}});on.disabled=off.disabled=false;};
 on.addEventListener('click',()=>set(true));off.addEventListener('click',()=>set(false));
 const alab=el('div','Node alerts in ATAK chat','control-label');alab.id='lbl-chatalerts';
 const arow=el('div',undefined,'toggle-row');arow.setAttribute('role','group');arow.setAttribute('aria-labelledby','lbl-chatalerts');
 const aon=el('button','ON','power'),aoff=el('button','OFF','power');aon.type=aoff.type='button';arow.append(aon,aoff);
 const anote=el('p','Off (default): battery, mesh and temperature warnings stay out of All Chat Rooms. On: the node posts them there for every phone on its Wi-Fi.','note');
 const aset=async want=>{aon.disabled=aoff.disabled=true;await act(box,'led',{chat_alerts:want},out,r=>{if(r.body.ok){out.textContent=`Node alerts in ATAK chat ${want?'on':'off'}.`;out.className='note ok';if(summary?.led)summary.led.chat_alerts=want;updateLed();}else{out.textContent=r.body.error||'Change failed';out.className='warn';}});aon.disabled=aoff.disabled=false;};
 aon.addEventListener('click',()=>aset(true));aoff.addEventListener('click',()=>aset(false));
 box.append(lab,row,el('div',undefined,'control-divider'),blab,bval,seg,el('div',undefined,'control-divider'),alab,arow,anote,out);
 ctl={on,off,levels,bval,aon,aoff};
}
function updateLed(){
 if(!ctl)buildLed();const led=summary?.led;const orb=$('led-orb');
 if(!led||!led.pattern){orb.style.background='#1a242b';orb.style.boxShadow='none';$('led-name').textContent='Unknown';$('led-reason').textContent='LED status unavailable';$('led-alert').textContent='';
  for(const b of [ctl.on,ctl.off,...Object.values(ctl.levels)])pressed(b,false);ctl.bval.textContent='--';return;}
 const c=LED_COLOURS[led.color]||'#1a242b',lit=led.enabled!==false;
 orb.style.background=lit?c:'#1a242b';orb.style.borderColor=c;orb.style.boxShadow=lit?`0 0 28px ${c}`:'none';
 $('led-name').textContent=led.color?(led.color==='magenta'?'Purple':led.color[0].toUpperCase()+led.color.slice(1)):'--';$('led-name').style.color=c;
 $('led-reason').textContent=(LED_MEANING[led.pattern]||led.pattern)+(led.gps_overlay?' · GPS fix pulse':'')+(led.charge_overlay?' · charging (green blinks)':'');
 $('led-alert').textContent=[lit?null:'Light switched off: the colour above is what it would show.',led.hardware===false?'No status LED detected on this node.':null].filter(Boolean).join(' ');
 pressed(ctl.on,led.enabled===true);pressed(ctl.off,led.enabled===false);pressed(ctl.aon,led.chat_alerts===true);pressed(ctl.aoff,led.chat_alerts!==true);
 for(const [v,b] of Object.entries(ctl.levels))pressed(b,led.brightness===Number(v));ctl.bval.textContent=has(led.brightness)?led.brightness+'%':'--';
}

// ---- Table cards: name | state | description
function trow(parent,name,state,detail,tone){const r=el('div',undefined,'trow');const st=el('div',undefined,'tstate');
 if(state instanceof Node)st.append(state);else{if(tone)st.append(el('span',undefined,'sdot '+tone));st.append(el('span',has(state)?String(state):'Unavailable','mono'+(tone?' '+tone:'')));}
 r.append(el('div',name,'tname'),st,el('div',detail||'','tdetail'));parent.append(r);return r;}
const SERVICE_INFO={openmanetd:'OpenMANET daemon and web UI',gpsd:'GPS receiver service',meshtastic:'LoRa / Meshtastic radio','pisugar-server':'PiSugar battery service',
 link_monitor:'L3 probe / link health','qos-rate-adapt':'Adaptive link rate','mcs-latch-watchdog':'Radio watchdog','link-demote':'Mesh link management','reboot-tracker':'System reboot tracking',gateway:'Internet gateway sharing',
 'fe-web':'Field Extension web page and API','fe-light':'Status light and PiSugar button','fe-phone-internet':'Phone internet helper','fe-lora':'LoRa fallback and radio control','fe-recovery':'Self-recovery service',
 'fe-halow-watchdog':'Reboots if the HaLow radio stops (at most 2 in 6 h)','fe-peer-watchdog':'Reboots if HaLow peers vanish for 20 min (at most 2 in 6 h)','fe-rate-latch':'Unsticks a stuck HaLow rate','fe-link-check':'Checks each mesh link','fe-link-demote':'Moves traffic off a weak 2.4 GHz link',
 'fe-mesh-guard':'Keeps mesh forwarding off (batman does the routing)','fe-traffic-shaping':'Traffic shaping (off until tested)','fe-power-off':'Turns the node fully off after a clean shutdown'};
const STATE_TEXT={RUNNING:['Running','ok'],RESTARTING:['Restarting','bad'],STOPPED:['Stopped','bad'],OFF:['Off','warn'],UNKNOWN:['Not running','warn']};
function renderServices(d){
 const box=$('services');box.replaceChildren();if(!d||typeof d!=='object'){trow(box,'Services',null,'Service list unavailable');return;}
 const names=Object.keys(d);
 const present=names.filter(k=>d[k]!=='UNAVAILABLE'),missing=names.filter(k=>d[k]==='UNAVAILABLE');
 if(!present.length)trow(box,'None','--','None of the known services are installed');
 for(const k of present){const v=d[k];const [t,tone]=STATE_TEXT[v]||[v[0]+v.slice(1).toLowerCase(),'bad'];trow(box,k,t,SERVICE_INFO[k],tone);}
 if(missing.length){const det=el('details',undefined,'tdetails');det.append(el('summary',`Not installed (${missing.length})`),el('p',missing.join(', '),'note mono'));box.append(det);}
}
function memLine(m){const g=k=>{const x=new RegExp('^'+k+':\\s+(\\d+)','m').exec(m||'');return x?Number(x[1]):null;};const t=g('MemTotal'),a=g('MemAvailable');return t&&a!==null?{pct:Math.round(100*(t-a)/t),text:`${Math.round((t-a)/1024)} of ${Math.round(t/1024)} MB`}:null;}
function renderHealth(){
 const box=$('health-rows');box.replaceChildren();const d=sys;
 const t=Number(d?.temperature_millicelsius)/1000;const tOk=has(d?.temperature_millicelsius)&&Number.isFinite(t);
 trow(box,'CPU Temperature',tOk?t.toFixed(1)+' °C':null,'Processor thermal state',!tOk?'muted':t>=80?'bad':t>=70?'warn':'ok');
 const load=d?.load?d.load.split(' ')[0]:null;trow(box,'CPU Load',load,'1 minute load average',load?'ok':'muted');
 const mem=memLine(d?.memory);trow(box,'Memory',mem?mem.pct+'%':null,mem?mem.text+' in use':'RAM currently in use',!mem?'muted':mem.pct>=90?'bad':mem.pct>=75?'warn':'ok');
 const h=summary?.halow;trow(box,'HaLow Interface',h?.available?(h.state||'Unknown'):'Not detected',h?.available?join(h.interface,has(h.frequency_mhz)?h.frequency_mhz.toFixed(1)+' MHz':null):'No Morse Micro radio found',h?.state==='ONLINE'?'ok':h?.available?'warn':'muted');
 trow(box,'Kernel',d?.kernel,d?.architecture);
 const rc=summary?.recovery;
 if(rc){trow(box,'HaLow watchdog',rc.settings?.['radio-watchdog']?'On':'Off',rc.radio_watchdog||'Reboots the node if the HaLow radio stops answering (at most twice in 6 hours)',/not answering|Rebooting/.test(rc.radio_watchdog||'')?'warn':rc.settings?.['radio-watchdog']?'ok':'muted');trow(box,'HaLow peer watchdog',rc.settings?.['peer-watchdog']?'On':'Off',rc.peer_watchdog||'Reboots the node if it loses all HaLow peers for 20 minutes (same reboot limit)',/lost|Rebooting/.test(rc.peer_watchdog||'')?'warn':rc.settings?.['peer-watchdog']?'ok':'muted');
  trow(box,'HaLow rate check',rc.settings?.['mcs-latch']?'On':'Off',rc.mcs_latch||'Re-joins a strong peer stuck at the lowest rate',/stuck|Re-joined/.test(rc.mcs_latch||'')?'warn':'ok');
  for(const l of rc.links?.links||[])trow(box,'Mesh link '+l.next_hop.slice(-5),l.state==='ok'?'OK':l.state==='fail'?'Failing':l.state==='suspect'?'Lossy':'Unknown',join(l.iface,has(l.loss)?l.loss+'% loss':null,l.kicked?'re-joined '+ago(l.kicked):null),l.state==='ok'?'ok':l.state==='unknown'?'muted':'warn');
  if(rc.qos)trow(box,'Traffic shaping',rc.settings?.qos?'On':'Off',rc.qos,'info');
  if(rc.mesh_guard)trow(box,'Mesh guard','OK',rc.mesh_guard,'ok');
  const rb=rc.reboots||[];if(rb.length)trow(box,'Last restart',/unexpected|watchdog/.test(rb[rb.length-1])?'Unplanned':'Clean',rb[rb.length-1],/unexpected|watchdog/.test(rb[rb.length-1])?'warn':'ok');}
}
function renderBackhaul(){
 const box=$('backhaul');box.replaceChildren();const dr=net?.parsed?.default_route;const inet=summary?.internet?.available;
 trow(box,'Connection',inet?'Online':'Offline','Internet / upstream connectivity',inet?'ok':'warn');
 trow(box,'Type',dr?backhaulType(dr.interface):'None','Detected backhaul method');
 trow(box,'Interface',dr?dr.interface:'None','Active default-route interface');
 trow(box,'Gateway',dr?dr.gateway||'direct':'None','Default route gateway');
}

// ---- Phone internet: node backhaul (auto) or the phone's own cellular, as on RC1
let eudc=null;
function buildEud(){
 const box=$('eud');box.replaceChildren();
 const out=el('p','','note');out.setAttribute('aria-live','polite');
 const grp=el('div',undefined,'toggle-row');grp.setAttribute('role','group');grp.setAttribute('aria-label','Phone internet');
 const auto=el('button','AUTO','power'),cell=el('button','CELL','power');auto.type=cell.type='button';grp.append(auto,cell);
 const r=trow(box,'Internet source',grp,'Auto: phones use the node internet when it has one, otherwise their own cellular. Cell: phones always use their own cellular data; the node carries mesh traffic only.');
 r.querySelector('.tdetail').append(out);
 const set=async mode=>{auto.disabled=cell.disabled=true;out.textContent='Applying…';out.className='note';
  await act(r,'eud/internet',{mode},out,res=>{const b=res.body;
   if(b.ok){out.textContent=`Set to ${mode==='cellular'?'cellular':'auto'}. Phones switch at their next Wi-Fi reconnect.`;out.className='note ok';if(summary?.eud){summary.eud.mode=mode;summary.eud.active=b.active;}}
   else{out.textContent=b.error||'Change failed';out.className='warn';}});
  auto.disabled=cell.disabled=false;updateEud();};
 auto.addEventListener('click',()=>set('auto'));cell.addEventListener('click',()=>set('cellular'));
 const now=trow(box,'Phones using','--','');
 eudc={auto,cell,now};
}
function updateEud(){
 if(!eudc)buildEud();const e=summary?.eud;
 pressed(eudc.auto,!!e&&e.mode!=='cellular');pressed(eudc.cell,!!e&&e.mode==='cellular');
 const st=eudc.now.querySelector('.tstate');st.replaceChildren();
 const cellular=e?.active==='cellular';
 st.append(el('span',undefined,'sdot '+(e?'info':'muted')),el('span',e&&e.active?(cellular?'Own cellular':'Node internet'):'Unavailable','mono'));
 const n=e?.clients?.length;
 eudc.now.querySelector('.tdetail').textContent=e?join(has(n)?n+(n===1?' phone':' phones')+' on the node Wi-Fi':null,e.mode==='auto'&&cellular?'Node has no internet right now':null):'';
}

// ---- LoRa: radio, fallback, Open/Covert/Dark, speed, team channel, guest radios, radios heard
const REGIONS=['US','EU_433','EU_868','CN','JP','ANZ','KR','TW','RU','IN','NZ_865','TH','LORA_24','UA_433','UA_868','MY_433','MY_919','SG_923','PH_433','PH_868','PH_915','ANZ_433','KZ_433','KZ_863','NP_865','BR_902'];
const PROFILE_INFO={open:'Open: team channel plus the public channel. Public radios show on your map and see this radio\'s position.',
 covert:'Covert: same channels as Open, but this radio never puts its position on the public channel.',
 dark:'Dark: team channel only, on a private frequency. Keeps LoRa off the public channel and away from strangers.'};
const nice=v=>String(v).toLowerCase().replace(/_/g,' ').replace(/^./,c=>c.toUpperCase());
function ago(t){if(!t)return '';const s=Math.max(0,Math.round(Date.now()/1000-t));return s<60?s+' s ago':s<3600?Math.round(s/60)+' min ago':Math.round(s/3600)+' h ago';}
let lorac=null,lora=null,fleetOn=true;
function select(options,label,name=nice){const s=el('select');s.setAttribute('aria-label',label);for(const o of options){const x=el('option',name(o));x.value=o;s.append(x);}return s;}
function out(){const o=el('p','','note');o.setAttribute('aria-live','polite');return o;}
function btn(t,cls){const b=el('button',t,cls);b.type='button';return b;}
function loraAct(row,path,payload,o,okText,after){o.textContent='Sending to the radio…';o.className='note';
 return act(row,path,payload,o,r=>{o.textContent=r.body.ok?(r.body.note||okText):(r.body.error||'Change failed');o.className=r.body.ok?'note ok':'warn';if(r.body.ok&&after)after(r.body);});}
function wideRow(box,name,content,detail){const r=trow(box,name,content,detail);r.classList.add('wide');const o=out();r.querySelector('.tdetail').append(o);return [r,o];}
function drawQR(canvas,text){const q=qrcode(0,'M');q.addData(text);q.make();const n=q.getModuleCount(),cell=Math.max(3,Math.floor(232/(n+8))),size=(n+8)*cell;
 canvas.width=canvas.height=size;const g=canvas.getContext('2d');g.fillStyle='#fff';g.fillRect(0,0,size,size);g.fillStyle='#000';
 for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(q.isDark(y,x))g.fillRect((x+4)*cell,(y+4)*cell,cell,cell);}
function buildLora(){
 const box=$('lora');box.replaceChildren();
 // Radio status and alive check
 const ping=btn('Check radio');const pout=out();
 const radio=trow(box,'Radio','--','');ping.className='small';radio.querySelector('.tdetail').append(pout);radio.querySelector('.tname').append(' ',ping);
 const warn=el('ul',undefined,'warnings');warn.hidden=true;box.append(warn);
 ping.addEventListener('click',async()=>{ping.disabled=true;await loraAct(radio,'lora/ping',{},pout,'',b=>{pout.textContent=`Radio answered in ${b.ms} ms.`;});ping.disabled=false;});
 // Choose the radio when more than one is plugged in
 const dev=el('select');dev.setAttribute('aria-label','Radio for this node');const use=btn('Use this radio');
 const dbox=el('div',undefined,'btns');dbox.append(dev,use);
 const [drow,dout]=wideRow(box,'This node\'s radio',dbox,'More than one USB radio is plugged in. The node will not guess which one is its own.');
 use.addEventListener('click',async()=>{use.disabled=true;await loraAct(drow,'lora/device',{device:dev.value},dout,'Switching.');use.disabled=false;});
 // One-step setup for a new or foreign radio
 const sreg=select(REGIONS,'Region',v=>v.replace(/_/g,' '));sreg.className='narrow';sreg.value='US';
 const spre=select(['LONG_FAST','MEDIUM_FAST','SHORT_FAST','LONG_SLOW'],'Speed');
 const sprof=select(['dark','covert','open'],'LoRa mode',v=>v[0].toUpperCase()+v.slice(1));
 const sname=el('input');sname.placeholder='Team channel name';sname.maxLength=11;sname.setAttribute('aria-label','Team channel name');
 const swords=el('input');swords.type='password';swords.placeholder='Team passphrase';swords.autocomplete='new-password';swords.setAttribute('aria-label','Team passphrase');
 const sgo=btn('Set up this radio');const subox=el('div',undefined,'btns');subox.append(sreg,spre,sprof,sname,swords,sgo);
 const [surow,suout]=wideRow(box,'Set up this radio',subox,'This radio is not set up for Field Extension yet. Pick your region and the team channel name and passphrase every node uses. Dark (team only, private frequency) is the usual choice. The radio restarts once.');
 sgo.addEventListener('click',async()=>{sgo.disabled=true;await loraAct(surow,'lora/setup',{region:sreg.value,preset:spre.value,profile:sprof.value,name:sname.value,passphrase:swords.value},suout,'Radio set up.',()=>{swords.value='';});sgo.disabled=false;});
 // Fallback mode
 const mout=out();
 const grp=el('div',undefined,'toggle-row three');grp.setAttribute('role','group');grp.setAttribute('aria-label','LoRa fallback');
 const modes={};for(const [m,t] of [['auto','AUTO'],['always','ALWAYS'],['off','OFF']]){const b=btn(t,'power');modes[m]=b;grp.append(b);}
 const mrow=trow(box,'Fallback',grp,'Auto: phone positions, chat and markers go over LoRa when the mesh is down, or when a teammate is heard only on LoRa (ATAK with a Meshtastic radio). Always: also while the mesh is up. Off: radio left alone.');
 const mnow=el('p','','note');mrow.querySelector('.tdetail').append(mnow,mout);
 for(const [m,b] of Object.entries(modes))b.addEventListener('click',async()=>{Object.values(modes).forEach(x=>x.disabled=true);
  await loraAct(mrow,'lora/mode',{mode:m},mout,`Fallback set to ${m}.`,()=>{if(lora)lora.mode=m;});Object.values(modes).forEach(x=>x.disabled=false);updateLora();});
 // Other nodes' own radios on ATAK maps
 const ngrp=el('div',undefined,'toggle-row');const non=btn('ON','power'),noff=btn('OFF','power');ngrp.append(non,noff);
 const [nrow,nout]=wideRow(box,'Node radios in ATAK',ngrp,'Off (default): the other nodes\' own LoRa radios are kept off ATAK maps, since the node itself is not a person. Guests\' Meshtastic radios always show. This node only.');
 for(const [v,b] of [[true,non],[false,noff]])b.addEventListener('click',async()=>{non.disabled=noff.disabled=true;
  await loraAct(nrow,'lora/node-radios',{show:v},nout,'Saved.',()=>{if(lora)lora.node_radios_in_atak=v;});non.disabled=noff.disabled=false;updateLora();});
 // Change every node
 const fgrp=el('div',undefined,'toggle-row');const fon=btn('ON','power'),foff=btn('OFF','power');fgrp.append(fon,foff);
 const frow=trow(box,'Change every node',fgrp,'On: mode, speed and team channel changes are sent over LoRa to every node on the team channel, and all radios switch together. Off: only this node changes, which can split it from the team.');
 const setFleet=v=>{fleetOn=v;pressed(fon,v);pressed(foff,!v);};fon.addEventListener('click',()=>setFleet(true));foff.addEventListener('click',()=>setFleet(false));setFleet(true);
 const fleetNote=()=>fleetOn?'\n\nThis is sent to every node on the team channel, and they all switch in about 25 seconds.':'\n\nOnly this node changes.';
 // Open / Covert / Dark
 const pgrp=el('div',undefined,'toggle-row three');pgrp.setAttribute('role','group');pgrp.setAttribute('aria-label','LoRa mode');
 const profiles={};for(const p of ['open','covert','dark']){const b=btn(p.toUpperCase(),'power');profiles[p]=b;pgrp.append(b);}
 const prow=trow(box,'LoRa mode',pgrp,'');const pnow=el('p','','note'),pout2=out();prow.querySelector('.tdetail').append(pnow,pout2);
 for(const [p,b] of Object.entries(profiles))b.addEventListener('click',async()=>{const cur=lora?.radio;
  const split=cur?.profile==='custom'&&p!=='dark'?'\n\nWARNING: this radio was set up outside Field Extension'+(has(cur.frequency_mhz)?' ('+cur.frequency_mhz+' MHz)':'')+'. '+p.toUpperCase()+' moves it to the public LongFast frequency, so radios still on the current frequency will stop hearing it. DARK keeps the current frequency.':'';
  if(!confirm(PROFILE_INFO[p]+split+fleetNote()+'\n\nThe radio restarts to apply this.'))return;
  Object.values(profiles).forEach(x=>x.disabled=true);await loraAct(prow,'lora/profile',{profile:p,fleet:fleetOn},pout2,'Saved. The radio is restarting.');Object.values(profiles).forEach(x=>x.disabled=false);});
 // Region and speed
 const reg=select(REGIONS,'Region',v=>v.replace(/_/g,' '));reg.className='narrow';const pre=el('select');pre.setAttribute('aria-label','Speed');const rapply=btn('Apply');
 const rbox=el('div',undefined,'btns');rbox.append(reg,pre,rapply);
 const [rrow,rout]=wideRow(box,'Region and speed',rbox,'Every radio in the team must match. Long Fast is the usual choice; slower speeds reach further, faster ones carry more.');
 rapply.addEventListener('click',async()=>{if(!confirm(`Set LoRa to ${reg.value.replace(/_/g,' ')}, ${pre.selectedOptions[0]?.textContent||pre.value}?`+fleetNote()))return;
  rapply.disabled=true;await loraAct(rrow,'lora/radio',{region:reg.value,preset:pre.value,fleet:fleetOn},rout,'Saved. The radio is restarting.');rapply.disabled=false;});
 // Team channel, passphrase, share code
 const cname=el('input');cname.placeholder='Team channel name';cname.maxLength=11;cname.setAttribute('aria-label','Team channel name');
 const words=el('input');words.type='password';words.placeholder='Team passphrase';words.autocomplete='new-password';words.setAttribute('aria-label','Team passphrase');
 const capply=btn('Apply'),share=btn('Share code'),cwipe=btn('Wipe key');cwipe.classList.add('danger');
 const cbox=el('div',undefined,'btns');cbox.append(cname,words,capply,share,cwipe);
 const [crow,cout]=wideRow(box,'Team channel',cbox,'Only radios with the same name and passphrase can read team traffic. To change the passphrase on every node at once, leave "Change every node" on. After a node is lost, turn "Change every node" off and set the new passphrase on each node by hand or with the share code: a change sent over LoRa travels on the old key, which the lost node still has.');
 const qr=el('canvas');qr.className='qr';qr.hidden=true;crow.querySelector('.tdetail').append(qr);
 capply.addEventListener('click',async()=>{if(!confirm(`Set the team channel to "${cname.value}" with this passphrase?`+fleetNote()+'\n\nRadios with a different name or passphrase stop hearing this one. In Dark mode the frequency also comes from the name, so a new name means a new frequency.'))return;
  capply.disabled=true;await loraAct(crow,'lora/channel',{security:'private',name:cname.value,passphrase:words.value,fleet:fleetOn},cout,'Team channel saved.');words.value='';capply.disabled=false;});
 cwipe.addEventListener('click',async()=>{if(!confirm('Wipe the team key from THIS node?\n\nIts radio goes back to the public channel only and every remembered sign-in here is cleared. Use it before handing the node to someone outside the team.'))return;
  await loraAct(crow,'lora/wipe',{target:'self'},cout,'Team key wiped.');keepToken(null);});
 share.addEventListener('click',async()=>{if(!qr.hidden){qr.hidden=true;share.textContent='Share code';return;}
  share.disabled=true;await loraAct(crow,'lora/share',{},cout,'',b=>{drawQR(qr,b.url);qr.hidden=false;share.textContent='Hide code';
   cout.textContent='Scan with the Meshtastic app (Channels, then the QR button). '+b.note;});share.disabled=false;});
 // Send a message
 const msg=el('input');msg.placeholder='Message to the team channel';msg.maxLength=200;msg.setAttribute('aria-label','Message');
 const send=btn('Send');const sbox=el('div',undefined,'btns');sbox.append(msg,send);
 const [srow,sout]=wideRow(box,'Send message',sbox,'Shows on Meshtastic apps and in ATAK All Chat Rooms on other nodes.');
 send.addEventListener('click',async()=>{if(!msg.value)return;send.disabled=true;await loraAct(srow,'lora/send',{text:msg.value},sout,'Sent.',()=>{msg.value='';});send.disabled=false;});
 // Guest and team radios
 const gdev=el('select');gdev.setAttribute('aria-label','Radio to set up');
 const glong=el('input');glong.placeholder='Name (e.g. Bob)';glong.maxLength=36;glong.setAttribute('aria-label','Radio name');
 const gshort=el('input');gshort.placeholder='Short';gshort.maxLength=4;gshort.setAttribute('aria-label','Short name, up to 4 characters');
 const gset=btn('Set up radio'),gall=btn('Re-sync all');
 const gbox=el('div',undefined,'btns');gbox.append(gdev,glong,gshort,gset,gall);
 const [grow,gout]=wideRow(box,'Guest radio',gbox,'Plug a friend\'s Meshtastic radio into this node. It gets the team channel, key and speed, and sends its GPS position every 2 minutes. Re-sync all does the same for every extra radio plugged in (a USB hub), keeping their names.');
 const gprog=el('div');grow.querySelector('.tdetail').append(gprog);
 gset.addEventListener('click',async()=>{if(!gdev.value)return;gset.disabled=true;
  await loraAct(grow,'lora/provision',{radios:[{path:gdev.value,long_name:glong.value,short_name:gshort.value}]},gout,'Setting up the radio…');gset.disabled=false;});
 gall.addEventListener('click',async()=>{const list=[...gdev.options].map(o=>({path:o.value}));if(!list.length)return;gall.disabled=true;
  await loraAct(grow,'lora/provision',{radios:list},gout,'Setting up the radios…');gall.disabled=false;});
 const heard=el('div');box.append(heard);
 lorac={surow,radio,warn,ping,modes,mnow,non,noff,nrow,profiles,pnow,reg,pre,cname,dev,drow,gdev,grow,gprog,heard,
  rows:[mrow,nrow,frow,prow,rrow,crow,srow],setupHides:[frow,prow,rrow,crow]};
}
function bar(pct){const b=el('div',undefined,'pbar');const f=el('div');f.style.width=Math.max(0,Math.min(100,pct))+'%';b.append(f);return b;}
function fillSelect(s,items,value,label){const cur=document.activeElement===s?s.value:value;s.replaceChildren();for(const it of items){const o=el('option',label(it));o.value=it.path;s.append(o);}if(cur)s.value=cur;}
function updateLora(){
 if(!lorac)buildLora();const d=lora,r=d?.radio;const st=lorac.radio.querySelector('.tstate'),det=lorac.radio.querySelector('.tdetail');st.replaceChildren();
 const ok=r?.connected;
 st.append(el('span',undefined,'sdot '+(ok?(d.warnings?.length?'warn':'ok'):d?.available?'warn':'muted')),el('span',ok?'Connected':d?.available?'Not connected':'Unavailable','mono'));
 const h0=d?.health;
 const line=ok?join(r.name,r.node_id,r.firmware?'firmware '+r.firmware:null,has(h0?.ping_ms)?'answered in '+h0.ping_ms+' ms '+ago(h0.checked):null):(d?.message||r?.error||'');
 let t=det.querySelector('.line');if(!t){t=el('span','','line');det.prepend(t);}t.textContent=line;
 lorac.ping.hidden=!ok;
 const w=d?.warnings||[];lorac.warn.replaceChildren(...w.map(x=>el('li',x)));lorac.warn.hidden=!w.length;
 const devices=d?.devices||[];
 const choose=!ok&&(d?.choices?.length||0)>1;lorac.drow.hidden=!choose;
 if(choose)fillSelect(lorac.dev,d.choices,null,x=>x.name);
 lorac.surow.hidden=!(ok&&d?.needs_setup);
 for(const row of lorac.rows)row.hidden=!d?.available||!r?.channels;
 const others=devices.filter(x=>!x.this_node);
 lorac.grow.hidden=!ok||!others.length||!!d?.needs_setup;
 if(d?.needs_setup)for(const row of lorac.setupHides)row.hidden=true;fillSelect(lorac.gdev,others,null,x=>x.name);
 const prov=d?.provisioning;lorac.gprog.replaceChildren();
 for(const g of prov?.radios||[]){const p=el('div',undefined,'prog');const stepName=['Waiting','Connecting','Reading its settings','Writing team settings','Restarting the radio','Checking'][g.step]||'';
  p.append(el('span',`${g.name}: ${g.status==='done'?'done':g.status==='failed'?'failed. '+(g.error||''):stepName}`,g.status==='failed'?'warn':g.status==='done'?'ok':'note'),bar(g.status==='done'?100:100*g.step/(g.steps+1)));lorac.gprog.append(p);}
 if(!d?.available||!r?.channels){lorac.heard.replaceChildren();return;}
 for(const [m,b] of Object.entries(lorac.modes))pressed(b,d.mode===m);
 pressed(lorac.non,d.node_radios_in_atak===true);pressed(lorac.noff,d.node_radios_in_atak===false);
 const lo=d.lora_only||[];
 lorac.mnow.textContent=d.mode==='off'?'Off.':d.sending_over_lora?'Sending phone positions, chat and markers over LoRa now'+(d.mode==='auto'&&d.mesh_up&&lo.length?', because '+(lo.length===1?lo[0]+' is':lo.length+' teammates are')+' only on LoRa ('+lo.slice(0,3).join(', ')+').':'.'):'Mesh is up: LoRa is standing by.';
 for(const [p,b] of Object.entries(lorac.profiles))pressed(b,r.profile===p);
 const team=(r.channels||[]).find(c=>c.security==='private');
 lorac.pnow.textContent=r.profile==='public'?'No team channel yet: set one below, then pick a mode.':
  r.profile==='custom'?join('Custom layout, set outside Field Extension. DARK keeps this frequency. OPEN and COVERT move to the public LongFast frequency, which radios still on this layout do not follow.',has(r.frequency_mhz)?r.frequency_mhz+' MHz':null):
  join(PROFILE_INFO[r.profile]||'',has(r.frequency_mhz)?r.frequency_mhz+' MHz':null);
 for(const b of Object.values(lorac.profiles))b.disabled=r.profile==='public';
 if(r.lora&&document.activeElement!==lorac.reg&&document.activeElement!==lorac.pre){
  lorac.reg.value=r.lora.region;lorac.pre.replaceChildren();
  for(const p of d.presets||[]){const o=el('option',join(p.name,has(p.frequency_mhz)?p.frequency_mhz+' MHz':null,p.bandwidth_khz+' kHz'));o.value=p.id;o.disabled=!p.fits;lorac.pre.append(o);}
  lorac.pre.value=r.lora.modem_preset;}
 if(team&&document.activeElement!==lorac.cname&&!lorac.cname.value)lorac.cname.value=team.name;
 const h=lorac.heard;h.replaceChildren();const c=d.counters||{};
 trow(h,'Traffic',`${c.sent_positions||0}/${c.heard_positions||0}`,join(`Positions sent/heard · chats ${c.sent_chats||0}/${c.heard_chats||0} · markers ${c.sent_markers||0}/${c.heard_markers||0}`,d.queued?d.queued+' waiting':null),'info');
 const air=d.airtime;if(air&&has(air.channel_util))trow(h,'Channel busy',air.channel_util+'%',join(has(air.air_util_tx)?'this radio '+air.air_util_tx+'%':null,air.channel_util>50?'Very busy: positions will be late':air.channel_util>25?'Busy: send fewer messages':'Fine'),air.channel_util>50?'bad':air.channel_util>25?'warn':'ok');
 for(const s of d.scheduled||[])trow(h,'Switching soon',s.what,`in ${s.in_s} s`,'warn');
 for(const s of (d.changes||[]).slice(-3).reverse())trow(h,s.error?'Change failed':'Changed',s.what,join(s.error,ago(s.time)),s.error?'warn':'muted');
 for(const s of (d.health?.repairs||[]).slice(-2).reverse())trow(h,'Self-repair',s.action,ago(s.time),'warn');
 const list=r.heard||[];
 if(!list.length)trow(h,'Radios heard','None','No other LoRa radios heard yet','muted');
 for(const n of list.slice(0,10)){const tr=d.traces?.[n.id];
  const hops=has(n.hops_away)?(n.hops_away===0?'direct':n.hops_away+'h'):null;
  const row=trow(h,n.long_name||n.short_name||n.id,has(n.snr)?'SNR '+n.snr.toFixed(1):'--',
   join(n.id,hops,ago(n.last_heard),tr?.state==='running'?'pinging…':tr?.state==='done'?`ping ${(tr.ms/1000).toFixed(1)} s, ${tr.hops_out} hop${tr.hops_out>1?'s':''}`:tr?.state==='failed'?'no answer':null),'info');
  const pb=btn('Ping');pb.className='small';pb.disabled=tr?.state==='running';
  pb.addEventListener('click',async()=>{const o=out();row.querySelector('.tdetail').append(o);pb.disabled=true;await loraAct(row,'lora/trace',{node:n.id},o,'Pinging…');});
  const wb=btn('Wipe');wb.className='small danger';
  wb.addEventListener('click',async()=>{if(!confirm(`Wipe the team key from ${n.long_name||n.id}?\n\nUse this only for a lost or captured node. If it is switched on and in LoRa range, it forgets the team channel and every sign-in. Afterwards give the rest of the team a new key.`))return;
   const o=out();row.querySelector('.tdetail').append(o);await loraAct(row,'lora/wipe',{target:n.id},o,'Wipe sent.');});
  row.querySelector('.tname').append(' ',pb,' ',wb);}
 for(const m of (d.recent||[]).slice(-5).reverse())trow(h,m.direction==='sent'?(m.delivered===true?'Sent ✓':m.delivered===false?'Not delivered ✗':'Sent'):'Heard',m.callsign,join(m.text,m.direction==='sent'&&m.delivered===null?'waiting for a radio to pass it on':null,ago(m.time)),m.delivered===false?'warn':'muted');
}
async function refreshLora(){try{lora=await getJSON('lora',10000);}catch{lora=null;}updateLora();}

// ---- Maintenance: auto power-on, reboot, shut down
let apo=null;
function buildMaintenance(){
 const box=$('maintenance');box.replaceChildren();
 // Node identity: callsign, ATAK team colour, role
 const iout=el('p','','note');iout.setAttribute('aria-live','polite');
 const ibox=el('div',undefined,'btns');
 const icall=el('input');icall.type='text';icall.maxLength=40;icall.setAttribute('aria-label','Callsign');icall.placeholder='Callsign';
 const icol=el('select');icol.setAttribute('aria-label','Colour');
 for(const c of ['White','Yellow','Orange','Magenta','Red','Maroon','Purple','Dark Blue','Blue','Cyan','Teal','Green','Dark Green','Brown'])icol.append(new Option(c,c));
 const irole=el('select');irole.setAttribute('aria-label','Role');
 for(const r of ['Worn','Vehicle','Fixed','Unattended'])irole.append(new Option(r,r));
 const isave=el('button','Save');isave.type='button';ibox.append(icall,icol,irole,isave);
 isave.addEventListener('click',async()=>{await act(irow,'node/identity',{callsign:icall.value.trim(),color:icol.value,role:irole.value},iout,res=>{iout.textContent=res.body.ok?(res.body.note||'Saved.'):(res.body.error||'Request failed');iout.className=res.body.ok?'note ok':'warn';});icall.dataset.dirty='';refreshSummary();});
 for(const f of [icall,icol,irole])f.addEventListener('input',()=>{icall.dataset.dirty='1';});
 const irow=trow(box,'Node name',ibox,'The callsign ATAK users see for this node (alerts, map marker), its team colour and role. Leave the callsign empty to use the node name.');
 irow.querySelector('.tdetail').append(iout);
 // New nodes: one code from this node sets up another (mesh, 2.4 GHz mesh, LoRa team, callsign)
 const nshow=btn('Show setup code'),ncode=el('input'),ncall=el('input'),ngo=btn('Set up this node');
 ncode.placeholder='Paste a setup code (FENODE1:…)';ncode.setAttribute('aria-label','Setup code');ncode.autocomplete='off';
 ncall.placeholder='Callsign for this node';ncall.maxLength=40;ncall.setAttribute('aria-label','Callsign for this node');
 const nqr=el('canvas');nqr.className='qr';nqr.hidden=true;const ntext=el('input');ntext.readOnly=true;ntext.hidden=true;ntext.setAttribute('aria-label','Setup code text');
 const nbox=el('div',undefined,'btns');nbox.append(nshow,ncode,ncall,ngo);
 const [nrow,nout]=wideRow(box,'Add a new node',nbox,'On a running node, Show setup code and scan it with the ManetNode app on the new node (or copy the text). On the new node, paste or scan it here: it joins the mesh (HaLow and the 2.4 GHz stick), takes the LoRa team channel and reboots. The code holds the mesh password and team key, so only show it to your team.');
 nrow.querySelector('.tdetail').append(nqr,ntext);
 nshow.addEventListener('click',async()=>{if(!nqr.hidden){nqr.hidden=ntext.hidden=true;nshow.textContent='Show setup code';return;}
  nshow.disabled=true;await act(nrow,'node/setup-code',{},nout,res=>{const b=res.body;
   if(b.ok){drawQR(nqr,b.code);ntext.value=b.code;nqr.hidden=ntext.hidden=false;nshow.textContent='Hide setup code';nout.textContent=b.note;nout.className='note';}
   else{nout.textContent=b.error||'Request failed';nout.className='warn';}});nshow.disabled=false;});
 ngo.addEventListener('click',async()=>{if(!ncode.value.trim()){nout.textContent='Paste a setup code first.';nout.className='warn';return;}
  if(!confirm('Set up this node from the code? It joins that mesh and reboots.'))return;
  ngo.disabled=true;await act(nrow,'node/setup',{code:ncode.value.trim(),callsign:ncall.value.trim()||undefined,confirm:true},nout,res=>{const b=res.body;
   nout.textContent=b.ok?(b.note||'Done. Rebooting.'):(b.error||'Request failed');nout.className=b.ok?'note ok':'warn';if(b.ok)ncode.value='';});ngo.disabled=false;});
 // Maintenance mode: the status light shows white so nobody unplugs the node while it is worked on.
 const mout=el('p','','note');mout.setAttribute('aria-live','polite');
 const mgrp=el('div',undefined,'toggle-row');mgrp.setAttribute('role','group');mgrp.setAttribute('aria-label','Maintenance mode');
 const mon=el('button','ON 30 MIN','power'),moff=el('button','OFF','power');mon.type=moff.type='button';mgrp.append(mon,moff);
 const mrow=trow(box,'Maintenance mode',mgrp,'Status light shows white: the node is being worked on, do not unplug it. It also turns white by itself during updates, settings changes and the self-test. Turns itself off after 30 minutes.');
 const mnow=el('p','','note');mrow.querySelector('.tdetail').append(mnow,mout);
 const mset=async want=>{mon.disabled=moff.disabled=true;
  await act(mrow,'maintenance',{enabled:want,minutes:30},mout,res=>{const b=res.body;
   mout.textContent=b.ok?(want?'Maintenance mode on: the light is white.':'Maintenance mode off.'):(b.error||'Request failed');mout.className=b.ok?'note ok':'warn';
   if(b.ok&&summary?.led)summary.led.maintenance={active:b.active,until:b.until,reason:b.reason};updateMaintenance();});
  mon.disabled=moff.disabled=false;refreshSummary();};
 mon.addEventListener('click',()=>mset(true));moff.addEventListener('click',()=>mset(false));
 // Auto power-on (PiSugar), verified by read-back.
 const out=el('p','','note');out.setAttribute('aria-live','polite');
 const grp=el('div',undefined,'toggle-row');grp.setAttribute('role','group');grp.setAttribute('aria-label','Auto power-on');
 const on=el('button','ON','power'),off=el('button','OFF','power');on.type=off.type='button';grp.append(on,off);
 const r=trow(box,'Auto power-on',grp,'Start the node when external power returns');r.querySelector('.tdetail').append(out);
 const set=async want=>{on.disabled=off.disabled=true;out.textContent='Writing to the PiSugar and reading it back…';out.className='note';
  await act(r,'power/auto-power-on',{enabled:want},out,res=>{const b=res.body;
   if(b.verified===true){out.textContent=`Verified: the PiSugar reads ${want?'ON':'OFF'}.`;out.className='note ok';if(summary?.power)summary.power.auto_power_on=want;}
   else if(res.status===409){out.textContent='Another service manages the PiSugar on this node.';out.className='warn';}
   else{out.textContent='Not verified: the read-back did not match. '+(b.error||'The setting may not have changed.');out.className='warn';}
   updateMaintenance();renderTiles();});
  updateMaintenance();};
 on.addEventListener('click',()=>set(true));off.addEventListener('click',()=>set(false));
 // Battery size: used for time left until this node's own drain rate is measured
 const sout=el('p','','note');sout.setAttribute('aria-live','polite');
 const sbox=el('div',undefined,'btns');const sbtn={};
 const setSize=async mah=>{await act(srow,'battery/size',{mah},sout,res=>{sout.textContent=res.body.ok?(res.body.note||'Done.'):(res.body.error||'Request failed');sout.className=res.body.ok?'note ok':'warn';});refreshSummary();};
 for(const mah of [5000,8000,10000]){const b=el('button',mah.toLocaleString('en-US')+' mAh');b.type='button';sbtn[mah]=b;sbox.append(b);b.addEventListener('click',()=>setSize(mah));}
 const sother=el('button','Other');sother.type='button';sbox.append(sother);
 sother.addEventListener('click',async()=>{const cur=apo?.sother?.dataset.mah||'';
  const v=prompt('Battery size in mAh (1,000 to 50,000):',cur);if(v===null)return;const mah=Math.round(Number(String(v).replace(/[^0-9.]/g,'')));
  if(!(mah>=1000&&mah<=50000)){sout.textContent='Enter a size from 1,000 to 50,000 mAh.';sout.className='warn';return;}
  await setSize(mah);
  if(confirm('Battery size set to '+mah.toLocaleString('en-US')+' mAh.\n\nFor a battery other than 5,000, 8,000 or 10,000 mAh, a battery calibration makes the battery % and time left accurate for this pack.\n\nStart a calibration now? The battery must be full; after you unplug, the node runs until it switches itself off.'))
   await cal('start');});
 const srow=trow(box,'Battery size',sbox,'The pack fitted to this node. For another size, press Other and type it in. Time left uses it until the node has measured its own drain (about 10 minutes on battery).');
 const snow=el('p','','note');srow.querySelector('.tdetail').append(snow,sout);
 // Battery calibration: a voltage-to-% curve for this node's own pack
 const cout=el('p','','note');cout.setAttribute('aria-live','polite');
 const cstart=el('button','Calibrate');cstart.type='button';const ccancel=el('button','Cancel');ccancel.type='button';
 const creset=el('button','Use chip estimate');creset.type='button';
 const cbox=el('div',undefined,'btns');cbox.append(cstart,ccancel,creset);
 const crow=trow(box,'Battery calibration',cbox,'For a bigger or different battery. Charge to full, press Calibrate, unplug, and leave the node running until it switches itself off (about 12 hours on 10,000 mAh). The new curve is used from the next start.');
 crow.classList.add('wide');const cnow=el('p','','note');crow.querySelector('.tdetail').append(cnow,cout);
 const cal=async(action,question)=>{if(question&&!confirm(question))return;
  await act(crow,'battery/calibration',{action},cout,res=>{cout.textContent=res.body.ok?(res.body.note||'Done.'):(res.body.error||'Request failed');cout.className=res.body.ok?'note ok':'warn';});refreshSummary();};
 cstart.addEventListener('click',()=>cal('start','Start battery calibration?\n\nThe battery must be full. After you unplug, the node runs down until it switches itself off, so keep it somewhere it can stay on. Plugging in cancels the run.'));
 ccancel.addEventListener('click',()=>cal('cancel'));
 creset.addEventListener('click',()=>cal('reset','Forget the calibrated curve and use the PiSugar chip\'s own estimate?'));
 // Safe shutdown before the battery is flat
 const hout=el('p','','note');hout.setAttribute('aria-live','polite');
 const hbox=el('div',undefined,'btns');const hbtn={};
 for(const [pc,t] of [[0,'Off'],[5,'5%'],[10,'10%']]){const b=el('button',t);b.type='button';hbtn[pc]=b;hbox.append(b);
  b.addEventListener('click',async()=>{await act(hrow,'battery/safe-shutdown',{percent:pc},hout,res=>{hout.textContent=res.body.ok?(res.body.note||'Done.'):(res.body.error||'Request failed');hout.className=res.body.ok?'note ok':'warn';});refreshSummary();});}
 const hrow=trow(box,'Safe shutdown',hbox,'On battery, the node shuts itself down cleanly at this level instead of losing power suddenly (which can damage the SD card). It starts again when a charger is plugged in, if auto power-on is on.');
 const hnow=el('p','','note');hrow.querySelector('.tdetail').append(hnow,hout);
 // PiSugar button: long press = clean shutdown (PiSugar soft-shutdown mode)
 const kout=el('p','','note');kout.setAttribute('aria-live','polite');
 const kbox=el('div',undefined,'btns');const kon=el('button','Clean shutdown');kon.type='button';const koff=el('button','Cut power');koff.type='button';kbox.append(kon,koff);
 for(const [b,v] of [[kon,true],[koff,false]])b.addEventListener('click',async()=>{await act(krow,'battery/long-press',{enabled:v},kout,res=>{kout.textContent=res.body.ok?(res.body.note||'Done.'):(res.body.error||'Request failed');kout.className=res.body.ok?'note ok':'warn';});refreshSummary();});
 const krow=trow(box,'Button long press',kbox,'Clean shutdown: hold the PiSugar button and the node saves everything, blinks red and turns off. Cut power: the PiSugar default, which turns it off at once. Click, then hold also shuts down cleanly.');
 const know=el('p','','note');krow.querySelector('.tdetail').append(know,kout);
 // Field Extension updates from other nodes on the mesh
 const uout=el('p','','note');uout.setAttribute('aria-live','polite');
 const ucheck=el('button','Check');ucheck.type='button';const ugo=el('button','Update');ugo.type='button';
 const uon=el('button','Auto');uon.type='button';const uoff=el('button','Ask');uoff.type='button';
 const ubox=el('div',undefined,'btns');ubox.append(ucheck,ugo,uon,uoff);
 const urow=trow(box,'Updates from the mesh',ubox,'Update one node (or let the Mac do it), and the others can take the same version from it over the mesh. The download must match its checksum. Auto: install by itself. Ask: wait for Update here or in the plugin.');
 const unow=el('p','','note');urow.querySelector('.tdetail').append(unow,uout);
 const uact=async payload=>{await act(urow,'update/mesh',payload,uout,res=>{uout.textContent=res.body.ok?(res.body.note||'Done.'):(res.body.error||'Request failed');uout.className=res.body.ok?'note ok':'warn';});refreshSummary();};
 ucheck.addEventListener('click',()=>uact({action:'check'}));
 ugo.addEventListener('click',()=>{const o=summary?.mesh_update?.offer;if(o&&confirm(`Install Field Extension ${shownVer(o.version)} from ${o.node||o.ip}?\n\nThe node's services restart for about a minute.`))uact({action:'install',ip:o.ip});});
 uon.addEventListener('click',()=>uact({action:'auto',enabled:true}));uoff.addEventListener('click',()=>uact({action:'auto',enabled:false}));
 // Support bundle
 const dl=el('a','Open diagnostics','button');dl.href='/api/field/diagnostics';dl.target='_blank';dl.rel='noopener';
 trow(box,'Diagnostics',dl,'Everything someone helping you needs (versions, mesh, HaLow, LoRa, logs, restarts). No passwords or keys. Save the page and send it.');
 // Sign-in remembered on this device
 const lout=el('p','','note');lout.setAttribute('aria-live','polite');
 const lme=el('button','Sign out');lme.type='button';const lall=el('button','Sign out all devices','danger');lall.type='button';
 const lbox=el('div',undefined,'btns');lbox.append(lme,lall);
 const lrow=trow(box,'Sign-in',lbox,'You stay signed in on this device for 30 days of use. If a phone or laptop is lost, sign out all devices and change the node password.');
 lrow.querySelector('.tdetail').append(lout);
 const signout=async all=>{if(all&&!confirm('Sign out every phone and browser that is signed in to this node?'))return;
  if(all){await act(lrow,'logout',{all:true},lout,res=>{keepToken(null);lout.textContent=res.body.note||'Done.';lout.className='note ok';});return;}
  if(token)await post('logout',{}).catch(()=>{});keepToken(null);lout.textContent='Signed out.';lout.className='note ok';};
 lme.addEventListener('click',()=>signout(false));lall.addEventListener('click',()=>signout(true));
 const mk=(name,label,path,question,done,detail)=>{const pout=el('p','','note');pout.setAttribute('aria-live','polite');
  const b=el('button',label,'danger');b.type='button';const row=trow(box,name,b,detail);row.querySelector('.tdetail').append(pout);
  b.addEventListener('click',async()=>{if(!confirm(question))return;b.disabled=true;await act(row,path,{confirm:true},pout,res=>{pout.textContent=res.body.ok?done:(res.body.error||'Request failed');pout.className='warn';});b.disabled=false;});};
 mk('Reboot node','Reboot','node/reboot','Reboot this node?\n\nThe mesh link and this Wi-Fi drop for about 1 minute. Reconnect and refresh afterwards.',
  'Rebooting. Back in about a minute: reconnect to the node Wi-Fi and refresh.','Mesh link drops for about 1 minute');
 mk('Shut down node','Shutdown','node/shutdown','Shut down this node?\n\nThe mesh link drops and the node stays off. To start it again, press the PiSugar power button on the node.',
  'Shutting down. Use the PiSugar button on the node to start it again.','Stays off until the PiSugar button is pressed');
 apo={mrow,mon,moff,mnow,irow,icall,icol,irole,krow,kon,koff,know,r,on,off,crow,cnow,cstart,ccancel,creset,srow,sbtn,snow,sother,hrow,hbtn,hnow,urow,unow,ugo,uon,uoff};
}
function updateMaintenance(){
 if(!apo)buildMaintenance();
 const mm=summary?.led?.maintenance;apo.mrow.hidden=!mm;if(mm){pressed(apo.mon,!!mm.until);pressed(apo.moff,!mm.until);
  const left=mm.until?Math.max(0,Math.round((mm.until*1000-Date.now())/60000)):null;
  apo.mnow.textContent=mm.until?`On: ${left} min left`:mm.active?'Light is white: '+({update:'an update is running',settings:'settings are being applied',diagnostics:'the self-test is running'}[mm.reason]||'busy'):'Off';}const p=summary?.power||{};const sugar=p.available&&String(p.source||'').startsWith('pisugar');
 apo.r.hidden=!p.available;apo.on.disabled=apo.off.disabled=!sugar;
 pressed(apo.on,sugar&&p.auto_power_on===true);pressed(apo.off,sugar&&p.auto_power_on===false);
 const mu=summary?.mesh_update;apo.urow.hidden=!mu;if(mu){pressed(apo.uon,mu.auto);pressed(apo.uoff,!mu.auto);apo.ugo.disabled=!mu.offer;
  apo.unow.textContent=join('This node: '+(shownVer(mu.installed)||'?'),mu.offer?`Newer: ${shownVer(mu.offer.version)} on ${mu.offer.node||mu.offer.ip}`:mu.checked?'Up to date with the mesh':null,mu.state,mu.error);}
 const g=p.safe_shutdown;apo.hrow.hidden=!g;if(g){for(const k in apo.hbtn)pressed(apo.hbtn[k],g.percent===Number(k));
  apo.hnow.textContent=join(g.low_for_s?'Battery low for '+g.low_for_s+' s':null,g.last?'Last safe shutdown: '+new Date(g.last.time*1000).toLocaleString()+' at '+g.last.percent+'%':null);}
 const id=summary?.identity;apo.irow.hidden=!id;if(id&&!apo.icall.dataset.dirty&&document.activeElement!==apo.icall){apo.icall.value=id.custom_callsign?id.callsign:'';apo.icall.placeholder=id.custom_callsign?'Callsign':(summary.node||'Callsign');apo.icol.value=id.color;apo.irole.value=id.role;}
 const lp=p.long_press_shutdown;apo.krow.hidden=!lp;if(lp){pressed(apo.kon,lp.enabled);pressed(apo.koff,!lp.enabled);
  apo.know.textContent=lp.hardware===null?'PiSugar mode not read yet':lp.hardware===lp.enabled?'PiSugar set: '+(lp.hardware?'long press asks for a clean shutdown':'long press cuts the power'):'Applying to the PiSugar (within 10 s)';}
 const e=p.estimate;apo.srow.hidden=!e;for(const k in apo.sbtn)pressed(apo.sbtn[k],!!e&&e.mah===Number(k));
 const custom=!!e&&!(e.mah in apo.sbtn);pressed(apo.sother,custom);apo.sother.dataset.mah=custom?e.mah:'';apo.snow.textContent=custom?'Set to '+e.mah.toLocaleString('en-US')+' mAh':'';
 const c=p.calibration;apo.crow.hidden=!c;if(!c)return;
 const hrs=c.running_s?(c.running_s/3600).toFixed(1)+' h':null;
 apo.cnow.textContent=c.state==='waiting_unplug'?'Waiting: unplug the node to start recording.':c.state==='running'?join('Recording the battery',hrs,c.samples+' readings'):
  join(c.curve?'Using a calibrated curve'+(c.made?' ('+c.made+')':''):'Using the PiSugar chip\'s own estimate',c.message);
 apo.cstart.hidden=!!c.state;apo.ccancel.hidden=!c.state;apo.creset.hidden=!c.curve||!!c.state;
}

// ---- Footer strip
function renderStrip(){
 const box=$('strip');box.replaceChildren();const h=summary?.halow||{},mip=meshIP(),ap=net?.parsed?.access_point;
 const cell=(t,v)=>{const c=el('div',undefined,'strip-cell');c.append(el('div',t,'label'),el('div',v||'--','subvalue mono'));box.append(c);};
 cell('Morse HaLow',h.available?join(has(h.frequency_mhz)?h.frequency_mhz.toFixed(1)+' MHz':null,has(h.bandwidth_mhz)?h.bandwidth_mhz+' MHz':null,h.state):'Not detected');
 cell('Mesh IP',mip?mip.addr:null);
 cell('EUD Wi-Fi',ap?join(ap.ssid,has(ap.channel)?'ch '+ap.channel:null):null);
}

// ---- OpenMANET updates (unchanged behaviour, table style)
function link(text,url){if(typeof url!=='string'||!url.startsWith('https://github.com/'))return el('span',text);const a=el('a',text);a.href=url;a.target='_blank';a.rel='noopener noreferrer';return a;}
function renderRelease(body,title,r){
 if(!r)return;trow(body,title,r.version,join(r.published_at?new Date(r.published_at).toLocaleDateString():null,'Field Extension: '+r.field_extensions.status),r.field_extensions.status==='tested'?'ok':'warn');
 const box=el('div',undefined,'tblock');box.append(el('p',r.field_extensions.detail,'note'));
 if(r.warnings.length){const ul=el('ul',undefined,'warnings');for(const w of r.warnings)ul.append(el('li',w));box.append(ul);}
 const p=el('p',undefined,'note');p.append(r.image?link('Image for this node',r.image.url):el('span','No matching image'),' · ',r.checksums?link('Checksums',r.checksums.url):el('span','No checksums'),' · ',link('Release notes',r.url));box.append(p);
 body.append(box);
}
function renderUpdates(d){
 const body=$('updates');body.replaceChildren();
 if(!d||d.available===false){trow(body,'Update check',null,'Unavailable on this host','muted');return;}
 trow(body,'Status',d.state,d.message,d.state==='UPDATE AVAILABLE'?'warn':d.state==='UP TO DATE'?'ok':'muted');
 if(d.error)body.append(el('p',d.error,'tblock warn'));
 const i=d.installed;trow(body,'Installed',i.version,'Field Extension on this version: '+i.field_extensions.status);
 trow(body,'Image type',i.image||'Unknown',i.image?(i.image_source==='node.conf'?'Set in node.conf':'Detected'):'Set OPENMANET_IMAGE in node.conf');
 trow(body,'HaLow driver',i.halow_driver,'Morse Micro driver version');
 renderRelease(body,'Latest stable',d.latest);renderRelease(body,'Pre-release',d.prerelease);
 for(const x of d.dependencies)trow(body,x.name,x.ok===true?'Working':x.ok===false?'Not responding':'Not detected','Used by Field Extension',x.ok===true?'ok':x.ok===false?'bad':'muted');
 const b=el('button','Check now');b.type='button';b.addEventListener('click',async()=>{b.disabled=true;try{renderUpdates(await getJSON('updates?refresh=1',30000));}catch{b.disabled=false;b.textContent='Check failed, try again';}});
 trow(body,'Last checked',b,d.checked_at?new Date(d.checked_at*1000).toLocaleString():'Never');
 const blk=el('div',undefined,'tblock');body.append(blk);renderInstall(blk,d);blk.append(el('p',d.install,'note'));
}
function renderInstall(body,d){
 const up=d.upgrade;if(!up||!up.enabled)return;
 const box=el('div',undefined,'release');box.append(el('h3','Install update'));body.append(box);
 const st=up.status||{};
 if(['DOWNLOADING','VERIFYING','INSTALLING'].includes(st.state)){
  box.append(el('p',`${st.state}: ${st.message}`,'warn'));
  if(st.state!=='INSTALLING'&&!poll)poll=setTimeout(async()=>{poll=null;try{renderUpdates(await getJSON('updates'));}catch{}},5000);
  if(st.state==='INSTALLING')box.append(el('p','When the node is back, reconnect to its Wi-Fi and refresh this page.','note'));
  return;
 }
 if(st.state==='FAILED')box.append(el('p','Last attempt failed: '+st.message+' The node was not changed.','warn'));
 if(up.locked){box.append(el('p',up.locked));return;}
 const v=d.latest.version;const warn=d.latest.warnings||[];let ack=null;
 box.append(el('p',`Installs OpenMANET ${v} and keeps this node's settings. The node reboots and is offline for a few minutes. There is no automatic rollback: if it fails, re-flash the SD card.`));
 if(warn.length){const l=el('label',undefined,'ack');ack=el('input');ack.type='checkbox';l.append(ack,' I have read the warnings above for this release');box.append(l);}
 const btn=el('button',`Install ${v}`,'danger');btn.type='button';const out=el('p','','note');box.append(btn,out);
 const install=async()=>{
  if(ack&&!ack.checked){out.textContent='Tick the box to acknowledge the warnings first.';return;}
  if(!confirm(`Install OpenMANET ${v} on ${d.installed.version?'this node (now '+d.installed.version+')':'this node'}? It will reboot.`))return;
  btn.disabled=true;const r=await post('openmanet/update',{confirm:true,version:v,acknowledge_warnings:!!(ack&&ack.checked)});
  if(r.status===401){btn.disabled=false;loginForm(box,install);return;}
  out.textContent=r.body.message||r.body.error||'Request failed';out.className=r.body.ok?'note':'warn';
  if(r.body.ok){renderUpdates(await getJSON('updates'));}else btn.disabled=false;
 };
 btn.addEventListener('click',()=>token?install():loginForm(box,install));
}

// ---- Refresh: summary every 15 s while visible; everything on load and on the button.
function stamp(failed){$('updated').textContent=`Checked ${new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})}${failed?' · some readings failed':''}`;}
function renderSummaryParts(ok){renderHeader(ok);renderTiles();updateLed();updateEud();updateMaintenance();renderHealth();renderBackhaul();renderStrip();}
async function refreshSummary(){let ok=true;try{summary=await getJSON('summary',20000);}catch{ok=false;}renderSummaryParts(ok);return ok;}
let busy=false;
async function refreshAll(){
 if(busy)return;busy=true;const button=$('refresh');button.disabled=true;let fails=0;
 const get=async k=>{try{return await getJSON(k);}catch{fails++;return null;}};
 const [s,n,svc,sy]=await Promise.all(['summary','network','services','system'].map(get));
 summary=s||summary;net=n||net;sys=sy||sys;
 renderSummaryParts(!!s);renderServices(svc);
 stamp(fails);button.disabled=false;busy=false;
 get('updates').then(renderUpdates);
 refreshLora();
}
buildLed();buildLora();buildMaintenance();
$('refresh').addEventListener('click',refreshAll);
refreshAll();
setInterval(async()=>{if(document.hidden||busy)return;stamp(!(await refreshSummary()));refreshLora();},15000);

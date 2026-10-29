'use strict';
// Mesh node launcher: lists every node on the mesh with its OpenMANET and Field Extension pages.
const $=id=>document.getElementById(id);
function el(tag,text,className){const n=document.createElement(tag);if(text!==undefined&&text!==null)n.textContent=text;if(className)n.className=className;return n;}
const KEY='manetnode.nodes';
function remembered(){try{return JSON.parse(localStorage.getItem(KEY))||[];}catch{return [];}}
function remember(list){try{localStorage.setItem(KEY,JSON.stringify(list));}catch{}}
async function json(url,ms){const r=await fetch(url,{signal:AbortSignal.timeout(ms)});if(!r.ok)throw Error(r.status);return r.json();}
// An opaque no-cors answer still proves something is listening there.
async function reachable(url){try{await fetch(url,{mode:'no-cors',signal:AbortSignal.timeout(3000)});return true;}catch{return false;}}

async function probe(n){
 const fe='http://'+n.ip+':8787/',om='http://'+n.ip+':8080/';
 const [hello,omOk]=await Promise.all([
  (n.self?json('/api/field/hello',5000):json(fe+'api/field/hello',3000)).catch(()=>null),
  reachable(om)]);
 if(!hello&&!omOk)return null; // phones and other clients on the mesh
 return {ip:n.ip,name:hello?.node||n.name||n.ip,self:!!n.self,fe:hello?fe:null,om:omOk?om:null,version:hello?.version?String(hello.version).replace(/^\d+:/,''):hello?.version};
}

function render(list,live){
 const box=$('list');box.replaceChildren();
 if(!list.length){box.append(el('p',live?'No nodes answered yet.':'Looking for nodes…','empty'));return;}
 for(const n of list){
  const card=el('section',undefined,'card node-card'+(live&&!n.up?' offline':''));
  const head=el('div',undefined,'node-head');
  head.append(el('h2',n.name),el('span',n.ip+(n.self?' · this node':''),'mono node-ip'));
  const btns=el('div',undefined,'btns');
  const link=(label,href)=>{const a=el('a',label,'linkbtn');if(href){a.href=href;a.target='_blank';a.rel='noopener';}else a.setAttribute('aria-disabled','true');return a;};
  btns.append(link('FieldExtension',n.fe),link('OpenManet',n.om));
  card.append(head,btns);box.append(card);
 }
}

async function scan(){
 $('updated').textContent='Looking for nodes…';
 const known=remembered();render(known,false);
 let mesh=[];
 try{mesh=(await json('/api/field/nodes',8000)).nodes||[];}catch{}
 const ips=new Map();for(const n of [...mesh,...known])if(!ips.has(n.ip))ips.set(n.ip,n);
 const found=(await Promise.all([...ips.values()].map(probe))).filter(Boolean);
 const up=new Set(found.map(n=>n.ip));
 // Keep remembered nodes that are out of range, greyed out, so their names stay familiar.
 const list=[...found.map(n=>({...n,up:true})),...known.filter(n=>!up.has(n.ip)).map(n=>({...n,up:false,fe:null,om:null}))];
 list.sort((a,b)=>(b.self-a.self)||(b.up-a.up)||a.name.localeCompare(b.name));
 remember(list.map(({ip,name})=>({ip,name})));
 render(list,true);
 $('updated').textContent=found.length+(found.length===1?' node':' nodes')+' answering · '+new Date().toLocaleTimeString();
}
$('refresh').addEventListener('click',scan);
scan();setInterval(scan,30000);

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const shell = fs.readFileSync('app/src/main/assets/instagram-shell.js', 'utf8');
const blocking = fs.readFileSync('app/src/main/assets/distraction-blocking.js', 'utf8');

function page(path = '/', options = {}) {
  const messages = [], timers = [], assignments = [], events = {}, observers = [];
  const location = {origin:'https://www.instagram.com', pathname:path,
    get href(){return this.origin+this.pathname;},
    assign(value){assignments.push(value);this.pathname=value;},
    replace(value){assignments.push(value);this.pathname=value;}};
  const history = {};
  for (const method of ['pushState','replaceState']) history[method] = (state,title,url) => {
    if(url!=null) location.pathname = new URL(url,location.href).pathname;
  };
  const links = (options.links || []).map(item => ({href:location.origin+item.path,
    textContent:item.label||'',getAttribute:()=>item.aria||'',querySelector:()=>item.profileIcon?{}:null,
    closest:selector=>item.inArticle?{}:item.inMain && selector==='main'?{}:null,
    click(){item.clicked=true;if(!item.broken) history.pushState({},'',item.path);}}));
  const navs = (options.navs || []).map(item=>({
    hasAttribute:()=>!!item.marked,setAttribute:()=>item.marked=true,
    getBoundingClientRect:()=>({height:item.height,top:item.top}),
    querySelector:(s)=>s==='a[href="/"]' || s==='a[href="/direct/inbox/"]'? (item.landmarkLinks?{}:null) : item.containsContent?{}:null,
    style:{setProperty:()=>item.hidden=true}}));
  const document = {documentElement:options.noDocument?null:{toggleAttribute(){}},
    getElementById:()=>true,querySelectorAll(s){
      if(s==='a[href]')return links;
      if(s==='nav, [role="navigation"]')return navs;
      return [];
    },addEventListener:(name,callback)=>events[name]=callback};
  const window = {innerHeight:800,CleanGramShell:{postMessage:payload=>messages.push(JSON.parse(payload))},
    addEventListener:(name,callback)=>events[name]=callback};
  const context=vm.createContext({window,document,location,history,URL,
    setTimeout:callback=>timers.push(callback),requestAnimationFrame:callback=>callback(),
    MutationObserver:class{constructor(callback){observers.push(callback)}observe(){}}});
  if(options.withBlocking)vm.runInContext(blocking,context);
  vm.runInContext(shell,context);
  return {context,window,document,location,history,links,messages,assignments,events,
    flush:()=>timers.splice(0).forEach(fn=>fn())};
}

test('native Home and Messages use Instagram links and report actual SPA routes',()=>{
  const p=page('/',{links:[{path:'/direct/inbox/'},{path:'/'}],withBlocking:true});
  assert(p.window.__cleanGramShell.navigate('messages'));p.flush();
  assert.equal(p.location.pathname,'/direct/inbox/');assert.equal(p.messages.at(-1).path,'/direct/inbox/');
  assert(p.window.__cleanGramShell.navigate('home'));p.flush();
  assert.equal(p.location.pathname,'/');assert.equal(p.assignments.length,0);
});

test('normal website navigation is the fallback when no route link exists',()=>{
  const p=page('/');assert(p.window.__cleanGramShell.navigate('messages'));p.flush();
  assert.deepEqual(p.assignments,['/direct/inbox/']);
});

test('an authentication redirect is not replaced by a pending native navigation',()=>{
  const p=page('/');p.window.__cleanGramShell.navigate('messages');
  p.location.pathname='/accounts/login/';p.flush();assert.equal(p.assignments.length,0);
});

test('Profile uses a labelled self-profile link and never a feed avatar',()=>{
  const p=page('/',{links:[{path:'/other.person/'},{path:'/my.account/',profileIcon:true}]});
  assert(p.window.__cleanGramShell.navigate('profile'));p.flush();assert.equal(p.location.pathname,'/my.account/');
  const unknown=page('/',{links:[{path:'/other.person/'}]});
  assert.equal(unknown.window.__cleanGramShell.navigate('profile'),false);
  assert.equal(unknown.assignments.length,0);
});

test('native integration never hides a feed, large landmark, form or unknown navigation',()=>{
  const navs=[{height:700,top:0,landmarkLinks:true},{height:60,top:740,landmarkLinks:true,containsContent:true},
    {height:60,top:740,landmarkLinks:false},{height:64,top:736,landmarkLinks:true}];
  page('/',{navs});assert.deepEqual(navs.map(n=>!!n.hidden),[false,false,false,true]);
});

test('client-side Back and native navigation stay compatible with discovery blocking',()=>{
  const p=page('/direct/inbox/',{withBlocking:true});
  p.history.pushState({},'','/reels/');assert.equal(p.location.pathname,'/direct/inbox/');
  p.history.replaceState({},'','/explore/');assert.equal(p.location.pathname,'/direct/inbox/');
  p.history.pushState({},'','/p/shared/');assert.equal(p.location.pathname,'/p/shared/');
  p.location.pathname='/';p.events.popstate();assert.equal(p.messages.at(-1).path,'/');
});

test('reinjection is idempotent and document-start can safely run before the DOM exists',()=>{
  const p=page('/');const navigate=p.window.__cleanGramShell.navigate;
  vm.runInContext(shell,p.context);assert.equal(p.window.__cleanGramShell.navigate,navigate);
  const early=page('/',{noDocument:true});assert.equal(early.window.__cleanGramShell,undefined);
});

test('saved mute preference applies to dynamically inserted media without duplicate observers',()=>{
  const source=fs.readFileSync('app/src/main/assets/media-preferences.js','utf8');
  const media=[{muted:false}],events={},observers=[];
  const context=vm.createContext({window:{__cleangramMuted:true},document:{documentElement:{},
    querySelectorAll:()=>media,addEventListener:(name,fn)=>events[name]=fn},
    MutationObserver:class{constructor(fn){observers.push(fn)}observe(){}}});
  vm.runInContext(source,context);assert.equal(media[0].muted,true);
  context.window.__cleangramMuted=false;vm.runInContext(source,context);assert.equal(media[0].muted,false);
  media.push({muted:true});observers[0]();assert.equal(media[1].muted,false);assert.equal(observers.length,1);
});


test('an account named Profile inside a post is never treated as the self profile',()=>{
  const p=page('/',{links:[{path:'/profile/',label:'Profile',inArticle:true}]});
  assert.equal(p.window.__cleanGramShell.navigate('profile'),false);
});

test('repeated DOM refreshes do not flood native controls with unchanged routes',()=>{
  const p=page('/');const count=p.messages.length;
  p.window.__cleanGramShell.refresh();p.window.__cleanGramShell.refresh();
  assert.equal(p.messages.length,count);
});
